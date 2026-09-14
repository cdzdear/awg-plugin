package org.telegram.awg;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWGKeyRotationManager — автоматическая проверка и обновление AWG конфигурации.
 *
 * Как это работает:
 * 1. Клиент периодически (каждые N часов) отправляет POST /v1/check на сервер
 *    с HMAC-подписанным запросом, содержащим текущий PublicKey
 * 2. Сервер отвечает: valid=true/false, needs_rotation=true/false
 * 3. Если needs_rotation=true — клиент вызывает POST /v1/refresh
 * 4. Сервер возвращает зашифрованный новый конфиг
 * 5. Клиент расшифровывает (AES-256-GCM), применяет без перезапуска приложения
 *
 * Безопасность:
 * - Запросы подписаны HMAC-SHA256(client_id + public_key + timestamp, shared_secret)
 * - Timestamp в запросе защищает от replay-атак (окно ±5 минут)
 * - Конфиг зашифрован AES-256-GCM с per-client ключом
 * - SharedSecret никогда не передаётся по сети
 */
public class AWGKeyRotationManager {

    private static final String TAG = "AWGTelegram/KeyRotation";
    private static final String PREFS_NAME = "awg_key_rotation";
    private static final String PREF_CLIENT_ID = "client_id";
    private static final String PREF_SHARED_SECRET = "shared_secret"; // base64 AES-256
    private static final String PREF_SERVER_URL = "server_url";
    private static final String PREF_LAST_CHECK = "last_check_ts";
    private static final String PREF_KEY_EXPIRES_AT = "key_expires_at";
    private static final int CHECK_INTERVAL_HOURS = 6;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    public interface KeyRotationCallback {
        void onConfigUpdated(String newConfig);
        void onKeyValid(long expiresAt);
        void onError(String error);
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final ScheduledExecutorService scheduler;
    private final Handler mainHandler;
    private ScheduledFuture<?> checkTask;
    private KeyRotationCallback callback;

    private static volatile AWGKeyRotationManager instance;

    public static AWGKeyRotationManager getInstance(Context ctx) {
        if (instance == null) {
            synchronized (AWGKeyRotationManager.class) {
                if (instance == null) {
                    instance = new AWGKeyRotationManager(ctx.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private AWGKeyRotationManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AWG-KeyCheck-Thread");
            t.setDaemon(true);
            return t;
        });
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    // ──────────────────────────────────────────────────────────────
    // Configuration
    // ──────────────────────────────────────────────────────────────

    /**
     * Configure the key rotation manager.
     *
     * @param serverUrl    Base URL of the AWG key server, e.g. "https://your-server:8443"
     * @param clientId     Unique client identifier (UUID or similar)
     * @param sharedSecret Base64-encoded AES-256 key (32 bytes) for decrypting new configs
     */
    public void configure(String serverUrl, String clientId, String sharedSecret) {
        prefs.edit()
                .putString(PREF_SERVER_URL, serverUrl)
                .putString(PREF_CLIENT_ID, clientId)
                .putString(PREF_SHARED_SECRET, sharedSecret)
                .apply();
        Log.i(TAG, "Key rotation configured. Server: " + serverUrl + ", ClientID: " + clientId);
    }

    /**
     * Check if key rotation is properly configured.
     */
    public boolean isConfigured() {
        return prefs.getString(PREF_SERVER_URL, null) != null
                && prefs.getString(PREF_CLIENT_ID, null) != null
                && prefs.getString(PREF_SHARED_SECRET, null) != null;
    }

    /**
     * Set the callback for config updates and key status.
     */
    public void setCallback(KeyRotationCallback cb) {
        this.callback = cb;
    }

    // ──────────────────────────────────────────────────────────────
    // Scheduling
    // ──────────────────────────────────────────────────────────────

    /**
     * Start periodic key validity checks.
     * Checks immediately on start, then every CHECK_INTERVAL_HOURS hours.
     */
    public void startPeriodicChecks(AWGManager awgManager) {
        if (checkTask != null && !checkTask.isDone()) {
            checkTask.cancel(false);
        }

        checkTask = scheduler.scheduleAtFixedRate(() -> {
            if (isConfigured() && awgManager.isRunning()) {
                checkAndRotate(awgManager);
            }
        }, 0, CHECK_INTERVAL_HOURS, TimeUnit.HOURS);

        Log.i(TAG, "Periodic key checks started (every " + CHECK_INTERVAL_HOURS + "h)");
    }

    /**
     * Stop periodic checks.
     */
    public void stopPeriodicChecks() {
        if (checkTask != null) {
            checkTask.cancel(false);
            checkTask = null;
        }
    }

    /**
     * Trigger an immediate check (e.g., on app resume, or after connection failure).
     */
    public void checkNow(AWGManager awgManager) {
        scheduler.execute(() -> checkAndRotate(awgManager));
    }

    // ──────────────────────────────────────────────────────────────
    // Core logic
    // ──────────────────────────────────────────────────────────────

    private void checkAndRotate(AWGManager awgManager) {
        String serverUrl = prefs.getString(PREF_SERVER_URL, null);
        String clientId = prefs.getString(PREF_CLIENT_ID, null);
        String sharedSecret = prefs.getString(PREF_SHARED_SECRET, null);

        if (serverUrl == null || clientId == null || sharedSecret == null) {
            return;
        }

        // Extract public key from current config
        String currentConfig = awgManager.loadConfig();
        if (currentConfig == null) return;
        String publicKey = extractPublicKey(currentConfig);
        if (publicKey == null) {
            Log.w(TAG, "Cannot extract public key from config");
            return;
        }

        Log.d(TAG, "Checking key validity... ClientID=" + clientId);

        try {
            // Step 1: Check validity
            CheckResult result = checkValidity(serverUrl, clientId, publicKey, sharedSecret);

            prefs.edit()
                    .putLong(PREF_LAST_CHECK, System.currentTimeMillis())
                    .putLong(PREF_KEY_EXPIRES_AT, result.expiresAt)
                    .apply();

            if (!result.valid || result.needsRotation) {
                Log.i(TAG, "Key needs rotation. valid=" + result.valid
                        + " needsRotation=" + result.needsRotation);

                // Step 2: Fetch new config
                String newConfig = fetchNewConfig(serverUrl, clientId, publicKey, sharedSecret);
                if (newConfig != null) {
                    Log.i(TAG, "New config received. Applying without restart...");
                    awgManager.reloadConfig(newConfig, (running, port, error) -> {
                        if (error == null) {
                            Log.i(TAG, "Config rotated successfully. New SOCKS5 port: " + port);
                            if (callback != null) {
                                mainHandler.post(() -> callback.onConfigUpdated(newConfig));
                            }
                        } else {
                            Log.e(TAG, "Failed to apply new config: " + error);
                            if (callback != null) {
                                mainHandler.post(() -> callback.onError("Config apply failed: " + error));
                            }
                        }
                    });
                }
            } else {
                Log.d(TAG, "Key valid until " + result.expiresAt);
                if (callback != null) {
                    final long exp = result.expiresAt;
                    mainHandler.post(() -> callback.onKeyValid(exp));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Key check failed: " + e.getMessage());
            if (callback != null) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // API calls
    // ──────────────────────────────────────────────────────────────

    private static class CheckResult {
        boolean valid;
        boolean needsRotation;
        long expiresAt;
    }

    private CheckResult checkValidity(String serverUrl, String clientId,
            String publicKey, String sharedSecret) throws Exception {
        long timestamp = System.currentTimeMillis() / 1000;
        String token = computeHMAC(
                clientId + "|" + publicKey + "|" + timestamp,
                sharedSecret);

        JSONObject req = new JSONObject();
        req.put("client_id", clientId);
        req.put("public_key", publicKey);
        req.put("token", token);
        req.put("timestamp", timestamp);

        String responseBody = postJSON(serverUrl + "/v1/check", req.toString());
        JSONObject resp = new JSONObject(responseBody);

        CheckResult result = new CheckResult();
        result.valid = resp.optBoolean("valid", false);
        result.needsRotation = resp.optBoolean("needs_rotation", false);
        result.expiresAt = resp.optLong("expires_at", 0);
        return result;
    }

    private String fetchNewConfig(String serverUrl, String clientId,
            String publicKey, String sharedSecret) throws Exception {
        long timestamp = System.currentTimeMillis() / 1000;
        String token = computeHMAC(
                clientId + "|" + publicKey + "|" + timestamp,
                sharedSecret);

        JSONObject req = new JSONObject();
        req.put("client_id", clientId);
        req.put("public_key", publicKey);
        req.put("token", token);
        req.put("timestamp", timestamp);

        String responseBody = postJSON(serverUrl + "/v1/refresh", req.toString());
        JSONObject resp = new JSONObject(responseBody);

        String encryptedConfig = resp.getString("encrypted_config");
        String configHash = resp.getString("config_hash");

        // Decrypt the config
        String decryptedConfig = decryptConfig(encryptedConfig, sharedSecret);

        // Verify integrity
        String actualHash = sha256Base64(decryptedConfig.getBytes(StandardCharsets.UTF_8));
        if (!actualHash.equals(configHash)) {
            throw new SecurityException("Config hash mismatch — possible tampering!");
        }

        return decryptedConfig;
    }

    private String postJSON(String urlStr, String json) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "AWG-Telegram/1.0");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);

        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(body.length));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("HTTP " + code + " from " + urlStr);
        }

        byte[] resp = conn.getInputStream().readAllBytes();
        return new String(resp, StandardCharsets.UTF_8);
    }

    // ──────────────────────────────────────────────────────────────
    // Crypto
    // ──────────────────────────────────────────────────────────────

    /**
     * Compute HMAC-SHA256(message, base64Key) and return base64 result.
     */
    private String computeHMAC(String message, String sharedSecretBase64) throws Exception {
        byte[] keyBytes = Base64.decode(sharedSecretBase64, Base64.DEFAULT);
        SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(key);
        byte[] result = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(result, Base64.DEFAULT).trim();
    }

    /**
     * Decrypt AES-256-GCM encrypted config.
     * Format: base64(nonce[12] + ciphertext)
     */
    private String decryptConfig(String encryptedBase64, String sharedSecretBase64) throws Exception {
        byte[] keyBytes = Base64.decode(sharedSecretBase64, Base64.DEFAULT);
        byte[] combined = Base64.decode(encryptedBase64, Base64.DEFAULT);

        // Extract nonce (first 12 bytes) and ciphertext
        byte[] nonce = new byte[12];
        byte[] ciphertext = new byte[combined.length - 12];
        System.arraycopy(combined, 0, nonce, 0, 12);
        System.arraycopy(combined, 12, ciphertext, 0, ciphertext.length);

        SecretKey key = new SecretKeySpec(keyBytes, "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, nonce);
        cipher.init(Cipher.DECRYPT_MODE, key, spec);

        byte[] plaintext = cipher.doFinal(ciphertext);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private String sha256Base64(byte[] data) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        return Base64.encodeToString(md.digest(data), Base64.DEFAULT).trim();
    }

    // ──────────────────────────────────────────────────────────────
    // Config parsing helpers
    // ──────────────────────────────────────────────────────────────

    /**
     * Extract WireGuard public key from a config file.
     * Derives it from PrivateKey (WG keypairs are related).
     * For simplicity, we also store it in a comment or separate pref.
     */
    private String extractPublicKey(String config) {
        // Option A: look for # PublicKey comment (we add this when saving)
        for (String line : config.split("\n")) {
            line = line.trim();
            if (line.startsWith("# PublicKey = ")) {
                return line.substring("# PublicKey = ".length()).trim();
            }
        }
        // Option B: read from stored prefs
        return prefs.getString("cached_public_key", null);
    }

    /**
     * Cache the public key when we know it (call after tunnel starts).
     */
    public void cachePublicKey(String publicKey) {
        prefs.edit().putString("cached_public_key", publicKey).apply();
    }

    /**
     * Get the timestamp of the last key check.
     */
    public long getLastCheckTimestamp() {
        return prefs.getLong(PREF_LAST_CHECK, 0);
    }

    /**
     * Get key expiry timestamp.
     */
    public long getKeyExpiresAt() {
        return prefs.getLong(PREF_KEY_EXPIRES_AT, 0);
    }

    /**
     * Generate a new random client ID (UUID v4).
     */
    public static String generateClientId() {
        SecureRandom rng = new SecureRandom();
        byte[] bytes = new byte[16];
        rng.nextBytes(bytes);
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x40); // UUID v4
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        return String.format(
                "%08x-%04x-%04x-%04x-%012x",
                ((long) bytes[0] & 0xff) << 24 | ((long) bytes[1] & 0xff) << 16
                        | ((long) bytes[2] & 0xff) << 8 | ((long) bytes[3] & 0xff),
                ((int) bytes[4] & 0xff) << 8 | ((int) bytes[5] & 0xff),
                ((int) bytes[6] & 0xff) << 8 | ((int) bytes[7] & 0xff),
                ((int) bytes[8] & 0xff) << 8 | ((int) bytes[9] & 0xff),
                ((long) bytes[10] & 0xff) << 40 | ((long) bytes[11] & 0xff) << 32
                        | ((long) bytes[12] & 0xff) << 24 | ((long) bytes[13] & 0xff) << 16
                        | ((long) bytes[14] & 0xff) << 8 | ((long) bytes[15] & 0xff));
    }
}
