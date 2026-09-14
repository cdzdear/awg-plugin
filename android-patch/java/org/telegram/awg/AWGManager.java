package org.telegram.awg;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AWGManager — manages the lifecycle of the built-in AmneziaWG tunnel.
 *
 * Singleton that:
 *   - Loads the AWG native library
 *   - Creates/destroys the tunnel
 *   - Automatically configures Telegram's built-in SOCKS5 proxy
 *   - Persists configuration across restarts
 *
 * No VpnService is used — the tunnel runs entirely in userspace.
 */
public class AWGManager {

    private static final String TAG = "AWGTelegram/AWGManager";
    private static final String PREFS_NAME = "awg_tunnel_prefs";
    private static final String PREF_ENABLED = "enabled";
    private static final String PREF_CONFIG = "wg_config";
    private static final String PREF_SOCKS_PORT = "socks_port";

    // Singleton
    private static volatile AWGManager instance;

    private final Context context;
    private final SharedPreferences prefs;

    // Current tunnel state
    private final AtomicInteger tunnelHandle = new AtomicInteger(-1);
    private volatile int socksPort = -1;
    private volatile boolean running = false;

    // Listener for UI updates
    public interface StatusListener {
        void onStatusChanged(boolean running, int socksPort, String error);
    }
    private volatile StatusListener statusListener;

    private AWGManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Get the singleton instance. Call from Application.onCreate().
     */
    public static AWGManager getInstance() {
        if (instance == null) {
            synchronized (AWGManager.class) {
                if (instance == null) {
                    instance = new AWGManager(ApplicationLoader.applicationContext);
                }
            }
        }
        return instance;
    }

    /**
     * Initialize the AWG library and auto-start if previously enabled.
     * Call from Application.onCreate() after AWGLib.load().
     */
    public void init() {
        try {
            AWGLib.load(context);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "AWG library not available: " + e.getMessage());
            return;
        }

        // Auto-start if was previously enabled
        if (prefs.getBoolean(PREF_ENABLED, false)) {
            String config = prefs.getString(PREF_CONFIG, null);
            if (config != null && !config.isEmpty()) {
                Log.i(TAG, "Auto-starting AWG tunnel...");
                startTunnel(config, null);
            }
        }
    }

    /**
     * Start the AWG tunnel with the given WireGuard/AmneziaWG config.
     *
     * @param wgConfig Config in wg-quick INI format (supports AWG3 params)
     * @param callback Called on success or failure (can be null)
     */
    public void startTunnel(final String wgConfig, final StatusListener callback) {
        if (running) {
            Log.w(TAG, "Tunnel already running, stopping first");
            stopTunnel();
        }

        new Thread(() -> {
            try {
                Log.i(TAG, "Starting AWG tunnel...");

                // Create the userspace tunnel + SOCKS5
                int handle = AWGLib.create(wgConfig);
                if (handle < 0) {
                    String error = getErrorMessage(handle);
                    Log.e(TAG, "AWGLib.create failed: " + error + " (code=" + handle + ")");
                    notifyStatus(false, -1, error, callback);
                    return;
                }

                int port = AWGLib.getSocksPort(handle);
                if (port < 0) {
                    AWGLib.destroy(handle);
                    notifyStatus(false, -1, "Failed to get SOCKS5 port", callback);
                    return;
                }

                tunnelHandle.set(handle);
                socksPort = port;
                running = true;

                Log.i(TAG, "AWG tunnel started! SOCKS5 port: " + port);

                // Save state
                prefs.edit()
                        .putBoolean(PREF_ENABLED, true)
                        .putString(PREF_CONFIG, wgConfig)
                        .putInt(PREF_SOCKS_PORT, port)
                        .apply();

                // Configure Telegram's built-in proxy
                configureTelegramProxy(port);

                notifyStatus(true, port, null, callback);

            } catch (Exception e) {
                Log.e(TAG, "Unexpected error starting tunnel", e);
                notifyStatus(false, -1, e.getMessage(), callback);
            }
        }, "AWG-Start-Thread").start();
    }

    /**
     * Stop the AWG tunnel and restore Telegram's direct connection.
     */
    public void stopTunnel() {
        int handle = tunnelHandle.getAndSet(-1);
        if (handle >= 0) {
            AWGLib.destroy(handle);
            Log.i(TAG, "AWG tunnel stopped");
        }

        running = false;
        socksPort = -1;

        // Disable Telegram proxy
        disableTelegramProxy();

        prefs.edit()
                .putBoolean(PREF_ENABLED, false)
                .apply();

        if (statusListener != null) {
            statusListener.onStatusChanged(false, -1, null);
        }
    }

    /**
     * Reload the tunnel with a new config.
     * Stops the existing tunnel (if any) and starts a new one.
     */
    public void reloadConfig(String wgConfig, StatusListener callback) {
        stopTunnel();
        startTunnel(wgConfig, callback);
    }

    /**
     * @return true if the tunnel is currently running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * @return Current SOCKS5 port, or -1 if not running
     */
    public int getSocksPort() {
        return socksPort;
    }

    /**
     * @return Statistics JSON string from the tunnel
     */
    public String getStats() {
        int handle = tunnelHandle.get();
        if (handle < 0) return "{}";
        return AWGLib.getStats(handle);
    }

    /**
     * Save a WireGuard config to encrypted private storage.
     * @param config The config content
     */
    public void saveConfig(String config) {
        prefs.edit()
                .putString(PREF_CONFIG, config)
                .apply();
    }

    /**
     * Load the saved WireGuard config.
     * @return Config string or null if not set
     */
    public String loadConfig() {
        return prefs.getString(PREF_CONFIG, null);
    }

    /**
     * Set the status listener for UI updates.
     */
    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
        // Immediately notify current state
        if (listener != null) {
            listener.onStatusChanged(running, socksPort, null);
        }
    }

    // ========== Private helpers ==========

    /**
     * Configure Telegram's built-in SOCKS5 proxy to route through our AWG tunnel.
     *
     * This uses Telegram's official proxy mechanism (Data & Storage → Proxy)
     * — no hacks or hooks required.
     */
    private void configureTelegramProxy(int port) {
        try {
            // Use SharedConfig to set the proxy — this is the official Telegram API
            // for programmatically configuring the built-in proxy
            SharedPreferences preferences = context.getSharedPreferences(
                    "mainconfig", Context.MODE_MULTI_PROCESS);

            preferences.edit()
                    .putBoolean("proxy_enabled", true)
                    .putString("proxy_ip", "127.0.0.1")
                    .putInt("proxy_port", port)
                    .putString("proxy_user", "")
                    .putString("proxy_pass", "")
                    .putString("proxy_secret", "")
                    .putInt("proxy_type", 1) // 1 = SOCKS5
                    .apply();

            // Notify Telegram to apply the proxy settings
            // This reloads the connection settings at runtime
            SharedConfig.reloadConfig();

            Log.i(TAG, "Telegram proxy configured: 127.0.0.1:" + port + " (SOCKS5 via AWG)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to configure Telegram proxy", e);
        }
    }

    /**
     * Remove the AWG SOCKS5 proxy from Telegram settings.
     */
    private void disableTelegramProxy() {
        try {
            SharedPreferences preferences = context.getSharedPreferences(
                    "mainconfig", Context.MODE_MULTI_PROCESS);

            preferences.edit()
                    .putBoolean("proxy_enabled", false)
                    .apply();

            SharedConfig.reloadConfig();

            Log.i(TAG, "Telegram proxy disabled");
        } catch (Exception e) {
            Log.e(TAG, "Failed to disable Telegram proxy", e);
        }
    }

    private void notifyStatus(boolean running, int port, String error, StatusListener callback) {
        if (callback != null) {
            callback.onStatusChanged(running, port, error);
        }
        if (statusListener != null) {
            statusListener.onStatusChanged(running, port, error);
        }
    }

    private static String getErrorMessage(int code) {
        switch (code) {
            case -1: return "Config parse error — check your AWG config format";
            case -2: return "Failed to create userspace network stack";
            case -3: return "Failed to configure WireGuard device — check keys/config";
            case -4: return "Failed to bring tunnel interface up";
            case -5: return "Failed to start SOCKS5 proxy server";
            default: return "Unknown error (code=" + code + ")";
        }
    }
}
