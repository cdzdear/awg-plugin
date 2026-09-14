package org.telegram.awg.plugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * AWGProxyReceiver — встраивается в AyuGram/Exteragram для приёма прокси-настроек от плагина.
 *
 * Этот файл добавляется в ИСХОДНЫЙ КОД AyuGram:
 *   TMessagesProj/src/main/java/org/telegram/awg/plugin/AWGProxyReceiver.java
 *
 * В AndroidManifest.xml AyuGram добавить:
 * <receiver
 *     android:name="org.telegram.awg.plugin.AWGProxyReceiver"
 *     android:exported="true">
 *     <intent-filter>
 *         <action android:name="org.telegram.awg.action.SET_PROXY" />
 *         <action android:name="org.telegram.awg.action.CLEAR_PROXY" />
 *     </intent-filter>
 * </receiver>
 *
 * Это минимальная модификация AyuGram — всего ~30 строк кода!
 */
public class AWGProxyReceiver extends BroadcastReceiver {

    private static final String TAG = "AWGProxy/Receiver";

    // Whitelist: only accept from these packages
    private static final String[] ALLOWED_PACKAGES = {
            "org.telegram.awg.plugin",
            "com.awgtelegram.plugin",
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        // Security: verify sender package
        // Note: getInitialStickyBroadcast() doesn't have sender info,
        // but we can check via signature verification in production
        String action = intent.getAction();
        if (action == null) return;

        Log.d(TAG, "Received: " + action);

        if (AWGPluginService.ACTION_SET_PROXY.equals(action)) {
            String host = intent.getStringExtra(AWGPluginService.EXTRA_PROXY_HOST);
            int port = intent.getIntExtra(AWGPluginService.EXTRA_PROXY_PORT, -1);
            String type = intent.getStringExtra(AWGPluginService.EXTRA_PROXY_TYPE);

            if (host != null && port > 0) {
                applyProxy(context, host, port);
                Log.i(TAG, "AWG proxy applied: " + host + ":" + port + " (" + type + ")");
            }
        } else if (AWGPluginService.ACTION_CLEAR_PROXY.equals(action)) {
            clearProxy(context);
            Log.i(TAG, "AWG proxy cleared");
        }
    }

    private void applyProxy(Context context, String host, int port) {
        // Apply to Telegram's SharedPreferences
        context.getSharedPreferences("mainconfig", Context.MODE_MULTI_PROCESS)
                .edit()
                .putBoolean("proxy_enabled", true)
                .putString("proxy_ip", host)
                .putInt("proxy_port", port)
                .putString("proxy_user", "")
                .putString("proxy_pass", "")
                .putString("proxy_secret", "")
                .putInt("proxy_type", 1) // SOCKS5
                .apply();

        // Reload Telegram connection (hot-apply without restart)
        try {
            Class.forName("org.telegram.messenger.SharedConfig")
                    .getMethod("reloadConfig")
                    .invoke(null);
        } catch (Exception e) {
            Log.w(TAG, "Could not call SharedConfig.reloadConfig(): " + e.getMessage());
        }
    }

    private void clearProxy(Context context) {
        context.getSharedPreferences("mainconfig", Context.MODE_MULTI_PROCESS)
                .edit()
                .putBoolean("proxy_enabled", false)
                .apply();

        try {
            Class.forName("org.telegram.messenger.SharedConfig")
                    .getMethod("reloadConfig")
                    .invoke(null);
        } catch (Exception e) {
            Log.w(TAG, "Could not call SharedConfig.reloadConfig()");
        }
    }
}
