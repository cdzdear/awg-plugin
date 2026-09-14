package org.telegram.awg.plugin;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.awg.AWGLib;
import org.telegram.awg.AWGManager;
import org.telegram.awg.AWGKeyRotationManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Main activity for the AWG Plugin app.
 * Provides UI for configuring the tunnel, key rotation server, and status.
 */
public class AWGPluginActivity extends Activity implements AWGManager.StatusListener,
        AWGKeyRotationManager.KeyRotationCallback {

    private TextView statusLabel;
    private TextView keyStatusLabel;
    private Switch tunnelSwitch;
    private EditText configInput;
    private EditText serverUrlInput;
    private EditText clientIdInput;
    private EditText sharedSecretInput;
    private Button applyButton;
    private Button generateIdButton;
    private Button checkKeyButton;
    private TextView statsView;

    private AWGManager awgManager;
    private AWGKeyRotationManager keyRotation;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Init AWG
        try {
            AWGLib.load(this);
        } catch (UnsatisfiedLinkError e) {
            Toast.makeText(this, "libawg.so not found: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        awgManager = AWGManager.getInstance();
        keyRotation = AWGKeyRotationManager.getInstance(this);

        bindViews();
        loadSavedState();

        // Start the background service
        startService(new Intent(this, AWGPluginService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        awgManager.setStatusListener(this);
        keyRotation.setCallback(this);
        updateUI();

        // Start stats refresh
        startStatsRefresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopStatsRefresh();
    }

    private void bindViews() {
        statusLabel = findViewById(R.id.status_label);
        keyStatusLabel = findViewById(R.id.key_status_label);
        tunnelSwitch = findViewById(R.id.tunnel_switch);
        configInput = findViewById(R.id.config_input);
        serverUrlInput = findViewById(R.id.server_url_input);
        clientIdInput = findViewById(R.id.client_id_input);
        sharedSecretInput = findViewById(R.id.shared_secret_input);
        applyButton = findViewById(R.id.apply_button);
        generateIdButton = findViewById(R.id.generate_id_button);
        checkKeyButton = findViewById(R.id.check_key_button);
        statsView = findViewById(R.id.stats_view);

        tunnelSwitch.setOnCheckedChangeListener((v, checked) -> {
            if (checked && !awgManager.isRunning()) {
                startTunnel();
            } else if (!checked && awgManager.isRunning()) {
                stopTunnel();
            }
        });

        applyButton.setOnClickListener(v -> saveAndApplySettings());
        generateIdButton.setOnClickListener(v -> {
            clientIdInput.setText(AWGKeyRotationManager.generateClientId());
        });
        checkKeyButton.setOnClickListener(v -> {
            keyRotation.checkNow(awgManager);
            Toast.makeText(this, "Проверка ключа...", Toast.LENGTH_SHORT).show();
        });
    }

    private void loadSavedState() {
        // Load saved config
        String savedConfig = awgManager.loadConfig();
        if (savedConfig != null) {
            configInput.setText(savedConfig);
        }

        // Load key rotation settings
        // (stored in AWGKeyRotationManager's SharedPreferences)
    }

    private void startTunnel() {
        String config = configInput.getText().toString().trim();
        if (config.isEmpty()) {
            Toast.makeText(this, "Введите AWG конфиг!", Toast.LENGTH_SHORT).show();
            tunnelSwitch.setChecked(false);
            return;
        }
        awgManager.saveConfig(config);
        awgManager.startTunnel(config, this);
        statusLabel.setText("⏳ Подключение...");
    }

    private void stopTunnel() {
        awgManager.stopTunnel();
    }

    private void saveAndApplySettings() {
        String serverUrl = serverUrlInput.getText().toString().trim();
        String clientId = clientIdInput.getText().toString().trim();
        String sharedSecret = sharedSecretInput.getText().toString().trim();

        if (!serverUrl.isEmpty() && !clientId.isEmpty() && !sharedSecret.isEmpty()) {
            keyRotation.configure(serverUrl, clientId, sharedSecret);
            if (awgManager.isRunning()) {
                keyRotation.startPeriodicChecks(awgManager);
            }
            Toast.makeText(this, "Настройки ротации ключей сохранены", Toast.LENGTH_SHORT).show();
        }

        // Also save config if changed
        String config = configInput.getText().toString().trim();
        if (!config.isEmpty()) {
            awgManager.saveConfig(config);
        }
    }

    // ── StatusListener ────────────────────────────────────────────────────

    @Override
    public void onStatusChanged(boolean running, int socksPort, String error) {
        mainHandler.post(() -> {
            tunnelSwitch.setChecked(running);
            if (running) {
                statusLabel.setText("🟢 Туннель активен · SOCKS5 :"+socksPort);
                keyRotation.startPeriodicChecks(awgManager);
            } else if (error != null) {
                statusLabel.setText("🔴 Ошибка: " + error);
            } else {
                statusLabel.setText("⚫ Выключен");
            }
        });
    }

    // ── KeyRotationCallback ───────────────────────────────────────────────

    @Override
    public void onConfigUpdated(String newConfig) {
        mainHandler.post(() -> {
            configInput.setText(newConfig);
            keyStatusLabel.setText("🔄 Конфиг обновлён: " + now());
            Toast.makeText(AWGPluginActivity.this,
                    "AWG конфиг автоматически обновлён!", Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onKeyValid(long expiresAt) {
        mainHandler.post(() -> {
            String expStr = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                    .format(new Date(expiresAt * 1000));
            keyStatusLabel.setText("✅ Ключ действителен до " + expStr);
        });
    }

    @Override
    public void onError(String error) {
        mainHandler.post(() -> {
            keyStatusLabel.setText("⚠️ " + error);
        });
    }

    // ── Stats refresh ─────────────────────────────────────────────────────

    private Runnable statsRunnable;

    private void startStatsRefresh() {
        statsRunnable = new Runnable() {
            @Override
            public void run() {
                updateStats();
                mainHandler.postDelayed(this, 2000);
            }
        };
        mainHandler.post(statsRunnable);
    }

    private void stopStatsRefresh() {
        if (statsRunnable != null) {
            mainHandler.removeCallbacks(statsRunnable);
        }
    }

    private void updateStats() {
        if (awgManager.isRunning()) {
            String stats = awgManager.getStats();
            long lastCheck = keyRotation.getLastCheckTimestamp();
            String lastCheckStr = lastCheck > 0
                    ? new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(new Date(lastCheck))
                    : "никогда";
            statsView.setText("Статистика: " + stats + "\nПоследняя проверка ключа: " + lastCheckStr);
        } else {
            statsView.setText("");
        }
    }

    private void updateUI() {
        tunnelSwitch.setChecked(awgManager.isRunning());
        onStatusChanged(awgManager.isRunning(), awgManager.getSocksPort(), null);
    }

    private String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
    }
}
