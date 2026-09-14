package org.telegram.awg.plugin;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;

import org.telegram.awg.AWGLib;
import org.telegram.awg.AWGManager;
import org.telegram.awg.AWGKeyRotationManager;

/**
 * AWGPluginService — Background service that runs the AWG tunnel as a plugin.
 *
 * AyuGram / Exteragram plugin integration:
 *
 * AyuGram's plugin system allows external apps to:
 *   1. Receive broadcast intents from AyuGram when it starts/stops
 *   2. Send broadcast intents to AyuGram to configure its proxy settings
 *   3. Share SharedPreferences via a ContentProvider (same sharedUserId)
 *
 * This service:
 *   - Runs as a foreground service (no system VPN needed)
 *   - Listens for AyuGram lifecycle events
 *   - Configures AyuGram's proxy to use our AWG SOCKS5
 *   - Manages key rotation automatically
 *
 * TWO INTEGRATION MODES:
 *   A) sharedUserId mode: same sharedUserId="org.telegram" allows direct SharedPreferences access
 *   B) ContentProvider mode: AWGPlugin exposes a ContentProvider, AyuGram reads proxy settings
 *   C) Broadcast mode: plugin sends proxy config via broadcast, AyuGram applies it
 */
public class AWGPluginService extends Service {

    private static final String TAG = "AWGPlugin/Service";

    // ── Intent actions (plugin protocol) ──────────────────────────────────
    /** AyuGram broadcasts this when it starts (plugin should configure proxy) */
    public static final String ACTION_AYUGRAM_STARTED = "org.ayugram.action.APP_STARTED";
    /** AyuGram broadcasts this when it's going to background */
    public static final String ACTION_AYUGRAM_BACKGROUND = "org.ayugram.action.APP_BACKGROUND";
    /** Plugin sends this to configure AyuGram's proxy */
    public static final String ACTION_SET_PROXY = "org.telegram.awg.action.SET_PROXY";
    /** Plugin sends this to disable proxy */
    public static final String ACTION_CLEAR_PROXY = "org.telegram.awg.action.CLEAR_PROXY";
    /** AyuGram reads this to check if AWG plugin is active */
    public static final String ACTION_PLUGIN_STATUS = "org.telegram.awg.action.STATUS";

    // ── Extras ─────────────────────────────────────────────────────────────
    public static final String EXTRA_PROXY_HOST = "proxy_host";
    public static final String EXTRA_PROXY_PORT = "proxy_port";
    public static final String EXTRA_PROXY_TYPE = "proxy_type"; // "socks5"
    public static final String EXTRA_IS_ACTIVE = "is_active";
    public static final String EXTRA_SOCKS_PORT = "socks_port";

    private AWGManager awgManager;
    private AWGKeyRotationManager keyRotation;

    // Broadcast receiver for AyuGram lifecycle events
    private final BroadcastReceiver ayugramReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case ACTION_AYUGRAM_STARTED:
                    Log.i(TAG, "AyuGram started — configuring proxy");
                    onAyuGramStarted();
                    break;
                case ACTION_AYUGRAM_BACKGROUND:
                    Log.d(TAG, "AyuGram went to background");
                    break;
                case ACTION_PLUGIN_STATUS:
                    broadcastStatus();
                    break;
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        // Initialize AWG
        try {
            AWGLib.load(this);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "libawg.so not available: " + e.getMessage());
            stopSelf();
            return;
        }

        awgManager = AWGManager.getInstance();
        keyRotation = AWGKeyRotationManager.getInstance(this);

        // Register to receive AyuGram lifecycle broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_AYUGRAM_STARTED);
        filter.addAction(ACTION_AYUGRAM_BACKGROUND);
        filter.addAction(ACTION_PLUGIN_STATUS);
        registerReceiver(ayugramReceiver, filter);

        // Set up key rotation callback
        keyRotation.setCallback(new AWGKeyRotationManager.KeyRotationCallback() {
            @Override
            public void onConfigUpdated(String newConfig) {
                Log.i(TAG, "Key rotated, updating proxy config");
                // Re-advertise new SOCKS5 port to AyuGram after tunnel restarts
                if (awgManager.isRunning()) {
                    broadcastProxyConfig(awgManager.getSocksPort());
                }
            }

            @Override
            public void onKeyValid(long expiresAt) {
                Log.d(TAG, "Key valid until " + expiresAt);
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Key rotation error: " + error);
            }
        });

        // Auto-start tunnel if previously enabled
        awgManager.init();
        if (awgManager.isRunning()) {
            startKeyRotationChecks();
        }

        Log.i(TAG, "AWG Plugin Service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (action == null) return START_STICKY;

        switch (action) {
            case "start_tunnel":
                String config = intent.getStringExtra("wg_config");
                if (config != null) {
                    startTunnel(config);
                }
                break;
            case "stop_tunnel":
                stopTunnel();
                break;
            case "check_key":
                keyRotation.checkNow(awgManager);
                break;
            case "status":
                broadcastStatus();
                break;
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(ayugramReceiver);
        keyRotation.stopPeriodicChecks();
        super.onDestroy();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Core methods
    // ──────────────────────────────────────────────────────────────────────

    private void startTunnel(String config) {
        awgManager.startTunnel(config, (running, port, error) -> {
            if (running) {
                Log.i(TAG, "Tunnel started, port=" + port);
                broadcastProxyConfig(port);
                startKeyRotationChecks();

                // Write to shared prefs (AyuGram reads this if sharedUserId matches)
                writeProxyToSharedPrefs("127.0.0.1", port);
            } else {
                Log.e(TAG, "Tunnel start failed: " + error);
                broadcastClearProxy();
            }
        });
    }

    private void stopTunnel() {
        awgManager.stopTunnel();
        keyRotation.stopPeriodicChecks();
        broadcastClearProxy();
        writeProxyToSharedPrefs(null, -1);
    }

    private void startKeyRotationChecks() {
        if (keyRotation.isConfigured()) {
            keyRotation.startPeriodicChecks(awgManager);
            Log.i(TAG, "Key rotation checks started");
        }
    }

    private void onAyuGramStarted() {
        // When AyuGram starts, tell it about our active proxy
        if (awgManager.isRunning()) {
            int port = awgManager.getSocksPort();
            broadcastProxyConfig(port);
            writeProxyToSharedPrefs("127.0.0.1", port);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // AyuGram communication
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Send broadcast to AyuGram with proxy settings.
     * AyuGram must have a BroadcastReceiver registered for ACTION_SET_PROXY.
     */
    private void broadcastProxyConfig(int socksPort) {
        Intent intent = new Intent(ACTION_SET_PROXY);
        intent.putExtra(EXTRA_PROXY_HOST, "127.0.0.1");
        intent.putExtra(EXTRA_PROXY_PORT, socksPort);
        intent.putExtra(EXTRA_PROXY_TYPE, "socks5");
        intent.setPackage("com.exteragram.messenger"); // target AyuGram/Exteragram package
        sendBroadcast(intent);

        // Also try generic Telegram
        intent.setPackage("org.ayugram.messenger");
        sendBroadcast(intent);

        Log.d(TAG, "Proxy broadcast sent: 127.0.0.1:" + socksPort);
    }

    /** Tell AyuGram to disable proxy (when tunnel stops) */
    private void broadcastClearProxy() {
        Intent intent = new Intent(ACTION_CLEAR_PROXY);
        intent.setPackage("com.exteragram.messenger");
        sendBroadcast(intent);
        intent.setPackage("org.ayugram.messenger");
        sendBroadcast(intent);
    }

    /** Broadcast current status (response to STATUS request) */
    private void broadcastStatus() {
        Intent status = new Intent(ACTION_PLUGIN_STATUS + ".RESPONSE");
        status.putExtra(EXTRA_IS_ACTIVE, awgManager.isRunning());
        status.putExtra(EXTRA_SOCKS_PORT, awgManager.getSocksPort());
        sendBroadcast(status);
    }

    /**
     * Write proxy config to SharedPreferences.
     *
     * If the plugin APK and AyuGram have the same sharedUserId in AndroidManifest,
     * they can access each other's SharedPreferences directly.
     * This is the cleanest integration method.
     *
     * Add to AyuGram's AndroidManifest.xml:
     *   android:sharedUserId="org.telegram"
     * Add to this plugin's AndroidManifest.xml:
     *   android:sharedUserId="org.telegram"
     */
    private void writeProxyToSharedPrefs(String host, int port) {
        // Write to "mainconfig" which is what Telegram reads for proxy settings
        SharedPreferences prefs = getSharedPreferences("mainconfig", Context.MODE_WORLD_READABLE);
        SharedPreferences.Editor editor = prefs.edit();

        if (host != null && port > 0) {
            editor.putBoolean("proxy_enabled", true)
                    .putString("proxy_ip", host)
                    .putInt("proxy_port", port)
                    .putString("proxy_user", "")
                    .putString("proxy_pass", "")
                    .putString("proxy_secret", "")
                    .putInt("proxy_type", 1); // 1 = SOCKS5
        } else {
            editor.putBoolean("proxy_enabled", false);
        }

        editor.apply();
    }
}
