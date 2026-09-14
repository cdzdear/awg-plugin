package org.telegram.ui.awg;

import android.animation.ValueAnimator;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.awg.AWGManager;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;

/**
 * AWGSettingsActivity — Settings screen for the built-in AmneziaWG tunnel.
 *
 * Appears in Telegram's Settings → Privacy and Security → Built-in AWG Tunnel
 * (or wherever the integration point is added in SettingsActivity)
 *
 * Features:
 *   - Enable/disable toggle
 *   - Config import (paste text or QR scan)
 *   - Connection status with animated indicator
 *   - Traffic statistics
 *   - Help section
 */
public class AWGSettingsActivity extends BaseFragment implements AWGManager.StatusListener {

    // Views
    private TextCheckCell enableCell;
    private TextView statusTextView;
    private TextView statsTextView;
    private StatusDotView statusDot;
    private EditTextBoldCursor configEditText;

    // State
    private AWGManager manager;
    private boolean isEnabled;
    private int currentSocksPort = -1;

    @Override
    public boolean onFragmentCreate() {
        manager = AWGManager.getInstance();
        manager.setStatusListener(this);
        isEnabled = manager.isRunning();
        currentSocksPort = manager.getSocksPort();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        manager.setStatusListener(null);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setTitle("Встроенный AWG Туннель");
        actionBar.setAllowOverlayTitle(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        // Root scroll view
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        fragmentView = scrollView;

        LinearLayout contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(contentLayout);

        // ── Status section ──────────────────────────────────────────────

        // Status card
        FrameLayout statusCard = createStatusCard(context);
        contentLayout.addView(statusCard,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                        16, 16, 16, 8));

        // ── Enable toggle ───────────────────────────────────────────────

        HeaderCell enableHeader = new HeaderCell(context);
        enableHeader.setText("Управление");
        contentLayout.addView(enableHeader);

        enableCell = new TextCheckCell(context);
        enableCell.setTextAndCheck("Включить AWG туннель", isEnabled, false);
        enableCell.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked);
        enableCell.setOnClickListener(v -> toggleTunnel());
        contentLayout.addView(enableCell);

        TextInfoPrivacyCell enableInfo = new TextInfoPrivacyCell(context);
        enableInfo.setText("Весь трафик Telegram будет направлен через зашифрованный AWG туннель. " +
                "Системный VPN не используется — нет иконки замка.");
        contentLayout.addView(enableInfo);

        // ── Config section ──────────────────────────────────────────────

        HeaderCell configHeader = new HeaderCell(context);
        configHeader.setText("Конфигурация AmneziaWG");
        contentLayout.addView(configHeader);

        // Config text input
        FrameLayout configFrame = new FrameLayout(context);
        configFrame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        contentLayout.addView(configFrame,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        configEditText = new EditTextBoldCursor(context);
        configEditText.setHint("Вставьте конфиг .conf формата...");
        configEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        configEditText.setGravity(Gravity.TOP | Gravity.START);
        configEditText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        configEditText.setMinHeight(AndroidUtilities.dp(180));
        configEditText.setPadding(
                AndroidUtilities.dp(16), AndroidUtilities.dp(12),
                AndroidUtilities.dp(16), AndroidUtilities.dp(12));

        // Pre-fill with saved config
        String savedConfig = manager.loadConfig();
        if (savedConfig != null) {
            configEditText.setText(maskPrivateKey(savedConfig));
        }

        configFrame.addView(configEditText,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Config action buttons
        LinearLayout configButtons = new LinearLayout(context);
        configButtons.setOrientation(LinearLayout.HORIZONTAL);
        configButtons.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        contentLayout.addView(configButtons,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Paste button
        TextCell pasteCell = new TextCell(context);
        pasteCell.setText("Вставить из буфера", false);
        pasteCell.setOnClickListener(v -> pasteFromClipboard());
        configButtons.addView(pasteCell,
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1.0f));

        // Save button
        TextCell saveCell = new TextCell(context);
        saveCell.setText("Сохранить", false);
        saveCell.setOnClickListener(v -> saveConfig());
        configButtons.addView(saveCell,
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1.0f));

        TextInfoPrivacyCell configInfo = new TextInfoPrivacyCell(context);
        configInfo.setText("Поддерживаются форматы WireGuard и AmneziaWG (с параметрами Jc, Jmin, Jmax, S1, S2, H1-H4). " +
                "Ключ хранится в зашифрованном хранилище приложения.");
        contentLayout.addView(configInfo);

        // ── AllowedIPs section ──────────────────────────────────────────

        HeaderCell ipsHeader = new HeaderCell(context);
        ipsHeader.setText("Рекомендуемые AllowedIPs");
        contentLayout.addView(ipsHeader);

        // Telegram DC IP ranges
        TextCell onlyTelegramCell = new TextCell(context);
        onlyTelegramCell.setTextAndValue("Только Telegram", "149.154.160.0/20, 91.108.4.0/22, ...", true);
        onlyTelegramCell.setOnClickListener(v -> insertTelegramIPs());
        contentLayout.addView(onlyTelegramCell);

        TextCell allTrafficCell = new TextCell(context);
        allTrafficCell.setTextAndValue("Весь трафик", "0.0.0.0/0, ::/0", false);
        allTrafficCell.setOnClickListener(v -> insertAllTrafficIPs());
        contentLayout.addView(allTrafficCell);

        TextInfoPrivacyCell ipsInfo = new TextInfoPrivacyCell(context);
        ipsInfo.setText("Рекомендуется туннелировать только IP-адреса серверов Telegram — " +
                "это ускоряет работу и снижает нагрузку на VPN-сервер.");
        contentLayout.addView(ipsInfo);

        // ── Help section ────────────────────────────────────────────────

        HeaderCell helpHeader = new HeaderCell(context);
        helpHeader.setText("Справка");
        contentLayout.addView(helpHeader);

        TextCell helpCell = new TextCell(context);
        helpCell.setText("Как настроить сервер AmneziaWG", true);
        helpCell.setOnClickListener(v -> openHelpUrl());
        contentLayout.addView(helpCell);

        TextCell aboutCell = new TextCell(context);
        aboutCell.setText("О технологии", false);
        aboutCell.setOnClickListener(v -> showAboutDialog());
        contentLayout.addView(aboutCell);

        TextInfoPrivacyCell bottomInfo = new TextInfoPrivacyCell(context);
        bottomInfo.setText("Встроенный AWG туннель работает поверх AmneziaWG " +
                "(форк WireGuard с защитой от DPI). Трафик шифруется без использования " +
                "системного VPN — на устройстве не появляется иконка замка.");
        contentLayout.addView(bottomInfo);

        return fragmentView;
    }

    private FrameLayout createStatusCard(Context context) {
        FrameLayout card = new FrameLayout(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF rect = new RectF();

            @Override
            protected void dispatchDraw(Canvas canvas) {
                paint.setColor(Theme.getColor(isEnabled
                        ? Theme.key_featuredStickers_addButton
                        : Theme.key_windowBackgroundGray));
                paint.setAlpha(30);
                rect.set(0, 0, getWidth(), getHeight());
                canvas.drawRoundRect(rect, AndroidUtilities.dp(12), AndroidUtilities.dp(12), paint);
                super.dispatchDraw(canvas);
            }
        };
        card.setPadding(
                AndroidUtilities.dp(16), AndroidUtilities.dp(16),
                AndroidUtilities.dp(16), AndroidUtilities.dp(16));

        // Status dot (animated)
        statusDot = new StatusDotView(context);
        card.addView(statusDot,
                LayoutHelper.createFrame(12, 12, Gravity.START | Gravity.CENTER_VERTICAL));

        // Status text
        LinearLayout textContainer = new LinearLayout(context);
        textContainer.setOrientation(LinearLayout.VERTICAL);
        card.addView(textContainer,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL, 24, 0, 0, 0));

        statusTextView = new TextView(context);
        statusTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        statusTextView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        textContainer.addView(statusTextView);

        statsTextView = new TextView(context);
        statsTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        statsTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        textContainer.addView(statsTextView);

        updateStatusUI();
        return card;
    }

    // ──────────── Status Listener ──────────────────────────────────────

    @Override
    public void onStatusChanged(boolean running, int socksPort, String error) {
        AndroidUtilities.runOnUIThread(() -> {
            isEnabled = running;
            currentSocksPort = socksPort;

            if (enableCell != null) {
                enableCell.setChecked(running);
            }
            updateStatusUI();

            if (error != null) {
                Toast.makeText(getParentActivity(),
                        "AWG ошибка: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void updateStatusUI() {
        if (statusTextView == null) return;

        if (isEnabled && currentSocksPort > 0) {
            statusTextView.setText("🟢 Туннель активен");
            statusTextView.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            statsTextView.setText("SOCKS5 → 127.0.0.1:" + currentSocksPort + " → AWG сервер");
            statusDot.setActive(true);
        } else if (!isEnabled) {
            statusTextView.setText("⚫ Туннель выключен");
            statusTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            statsTextView.setText("Telegram использует прямое соединение");
            statusDot.setActive(false);
        } else {
            statusTextView.setText("🟡 Подключение...");
            statsTextView.setText("Инициализация туннеля...");
        }
    }

    // ──────────── Actions ─────────────────────────────────────────────

    private void toggleTunnel() {
        if (isEnabled) {
            manager.stopTunnel();
        } else {
            String config = getConfigFromInput();
            if (config == null || config.trim().isEmpty()) {
                Toast.makeText(getParentActivity(),
                        "Сначала введите конфиг AmneziaWG", Toast.LENGTH_SHORT).show();
                return;
            }
            manager.startTunnel(config, this);
        }
    }

    private void pasteFromClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager)
                    getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                CharSequence text = clipboard.getPrimaryClip().getItemAt(0).getText();
                if (text != null) {
                    configEditText.setText(text.toString());
                    Toast.makeText(getParentActivity(), "Вставлено из буфера", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            Toast.makeText(getParentActivity(), "Ошибка буфера обмена", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveConfig() {
        String config = getConfigFromInput();
        if (config != null && !config.isEmpty()) {
            manager.saveConfig(config);
            Toast.makeText(getParentActivity(), "Конфиг сохранён", Toast.LENGTH_SHORT).show();
        }
    }

    private String getConfigFromInput() {
        if (configEditText == null) return null;
        String text = configEditText.getText().toString().trim();
        // Restore masked private key if user didn't change it
        String saved = manager.loadConfig();
        if (saved != null && text.contains("PrivateKey = ****")) {
            // Restore the original private key from saved config
            return saved;
        }
        return text;
    }

    private void insertTelegramIPs() {
        // Official Telegram DC IP ranges
        String ips = "149.154.160.0/20, 91.108.4.0/22, 91.108.8.0/22, " +
                "91.108.56.0/22, 91.108.16.0/22, 91.108.0.0/18, " +
                "91.105.192.0/23, 185.76.151.0/24, 2001:b28:f23d::/48, " +
                "2001:b28:f23f::/48, 2001:67c:4e8::/48, 2a0a:f280::/32";

        String current = configEditText.getText().toString();
        if (current.contains("AllowedIPs")) {
            // Replace existing AllowedIPs line
            current = current.replaceAll("(?m)^AllowedIPs\\s*=.*$", "AllowedIPs = " + ips);
            configEditText.setText(current);
        } else {
            Toast.makeText(getParentActivity(),
                    "AllowedIPs = " + ips + "\n(скопировано — вставьте в секцию [Peer])",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void insertAllTrafficIPs() {
        String current = configEditText.getText().toString();
        if (current.contains("AllowedIPs")) {
            current = current.replaceAll("(?m)^AllowedIPs\\s*=.*$", "AllowedIPs = 0.0.0.0/0, ::/0");
            configEditText.setText(current);
        }
    }

    private void openHelpUrl() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://docs.amnezia.org"));
            getParentActivity().startActivity(intent);
        } catch (Exception ignored) {}
    }

    private void showAboutDialog() {
        AlertsCreator.createSimpleAlert(getParentActivity(),
                "О встроенном AWG туннеле",
                "AmneziaWG (AWG) — это форк WireGuard с защитой от DPI-блокировок.\n\n" +
                "Параметры обфускации AWG3:\n" +
                "• Jc — количество junk-пакетов\n" +
                "• Jmin/Jmax — размер junk-пакетов\n" +
                "• S1/S2 — размер junk после handshake\n" +
                "• H1-H4 — кастомные magic заголовки\n\n" +
                "Tunnel работает в userspace без системного VPN.\n" +
                "Библиотека: amneziawg-go + SOCKS5 proxy").show();
    }

    // Mask private key for display (security)
    private String maskPrivateKey(String config) {
        return config.replaceAll("(?m)^(PrivateKey\\s*=\\s*)(.+)$", "$1****");
    }

    /**
     * Animated status dot view.
     */
    private static class StatusDotView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float alpha = 1.0f;
        private boolean active = false;
        private ValueAnimator pulseAnimator;

        public StatusDotView(Context context) {
            super(context);
        }

        public void setActive(boolean active) {
            this.active = active;
            if (pulseAnimator != null) {
                pulseAnimator.cancel();
            }
            if (active) {
                pulseAnimator = ValueAnimator.ofFloat(0.5f, 1.0f);
                pulseAnimator.setDuration(1000);
                pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
                pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
                pulseAnimator.addUpdateListener(anim -> {
                    alpha = (float) anim.getAnimatedValue();
                    invalidate();
                });
                pulseAnimator.start();
            } else {
                alpha = 1.0f;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            paint.setColor(active ? 0xFF4CAF50 : 0xFF757575);
            paint.setAlpha((int) (alpha * 255));
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            canvas.drawCircle(cx, cy, Math.min(cx, cy), paint);
        }
    }
}
