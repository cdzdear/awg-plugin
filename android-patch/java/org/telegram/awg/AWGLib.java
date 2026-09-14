package org.telegram.awg;

import android.content.Context;
import android.util.Log;

/**
 * JNI bridge to the Go AWG shared library (libawg.so).
 *
 * The library provides a userspace AmneziaWG tunnel with a built-in
 * SOCKS5 proxy server — no Android VpnService required.
 *
 * Usage:
 *   1. AWGLib.load(context);           // load the .so
 *   2. int handle = AWGLib.create(cfg); // start tunnel
 *   3. int port = AWGLib.getSocksPort(handle); // get SOCKS5 port
 *   4. // Configure Telegram's built-in proxy: 127.0.0.1:port
 *   5. AWGLib.destroy(handle);          // tear down when done
 */
public class AWGLib {

    private static final String TAG = "AWGTelegram/AWGLib";
    private static boolean loaded = false;

    /**
     * Load the native library. Call once at app startup.
     * @param context Android context (used for native library loading)
     */
    public static synchronized void load(Context context) {
        if (loaded) return;
        try {
            System.loadLibrary("awg");
            loaded = true;
            Log.i(TAG, "libawg.so loaded. Version: " + version());
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load libawg.so: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Create a new AmneziaWG tunnel with embedded SOCKS5 proxy.
     *
     * @param wgConfig WireGuard/AmneziaWG config in wg-quick INI format.
     *                 Supports AWG3 obfuscation params: Jc, Jmin, Jmax, S1, S2, H1-H4
     * @return Tunnel handle (>0) on success, negative error code on failure:
     *         -1: config parse error
     *         -2: failed to create userspace netstack
     *         -3: failed to configure WireGuard device
     *         -4: failed to bring device up
     *         -5: failed to start SOCKS5 server
     */
    public static native int create(String wgConfig);

    /**
     * Get the local SOCKS5 port for the tunnel.
     *
     * After calling this, configure Telegram's built-in proxy:
     *   host: 127.0.0.1, port: returned value, type: SOCKS5
     *
     * @param handle Tunnel handle from {@link #create(String)}
     * @return Local SOCKS5 port number, or -1 if handle is invalid
     */
    public static native int getSocksPort(int handle);

    /**
     * Get tunnel statistics as a JSON string.
     * Example: {"rx":1024,"tx":2048,"port":12345}
     *
     * @param handle Tunnel handle from {@link #create(String)}
     * @return JSON string (caller must not free — GC managed)
     */
    public static native String getStats(int handle);

    /**
     * Destroy the tunnel and free all resources.
     * After this call, the handle is invalid.
     *
     * @param handle Tunnel handle from {@link #create(String)}
     */
    public static native void destroy(int handle);

    /**
     * Get the version string of the AWG library.
     * @return Version string like "amneziawg-telegram/1.0.0 go/go1.21.0"
     */
    public static native String version();

    // Private constructor — this is a static utility class
    private AWGLib() {}
}
