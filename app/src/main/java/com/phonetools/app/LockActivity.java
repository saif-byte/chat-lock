package com.phonetools.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen PIN / fingerprint screen shown over Instagram or X chats. */
public class LockActivity extends Activity {
    public static final String EXTRA_PKG = "pkg";
    private static final int PIN_MAX = 8;

    protected Prefs prefs;
    private String pkg;
    private final StringBuilder entered = new StringBuilder();
    private TextView dots, message;
    private Button bioButton;
    private boolean bioShownOnce;
    private CancellationSignal bioCancel;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Overridden by SettingsLockActivity. */
    protected boolean isSettingsMode() { return false; }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // No screenshots, and the recents thumbnail stays blank.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        prefs = new Prefs(this);
        pkg = getIntent().getStringExtra(EXTRA_PKG);
        setContentView(buildUi());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String p = intent.getStringExtra(EXTRA_PKG);
        if (p != null) pkg = p;
        bioShownOnce = false;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Our PIN screen is now on screen: drop the instant cover drawn by the service.
        if (hasFocus) GuardService.onLockScreenVisible();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateLockoutMessage();
        handler.postDelayed(GuardService::onLockScreenVisible, 500); // backup if focus is delayed
        if (!bioShownOnce && canUseBiometric()) {
            bioShownOnce = true;
            handler.postDelayed(this::showBiometric, 120);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        cancel();
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        root.setPadding(pad, dp(64), pad, pad);

        TextView lockIcon = text("🔒", 44, false);
        root.addView(lockIcon);

        // Deliberately generic: doesn't say which app or that chats are locked.
        TextView t = text("Verify it's you", 22, true);
        t.setPadding(0, dp(12), 0, dp(4));
        root.addView(t);

        message = text("Enter your PIN", 15, false);
        message.setAlpha(0.75f);
        root.addView(message);

        dots = text("", 30, true);
        dots.setLetterSpacing(0.3f);
        dots.setPadding(0, dp(20), 0, dp(20));
        dots.setMinHeight(dp(80));
        root.addView(dots);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "bio", "0", "del"};
        for (String k : keys) {
            Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(88);
            lp.height = dp(72);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            btn.setLayoutParams(lp);
            switch (k) {
                case "bio":
                    btn.setText("👆");   // fingerprint-ish
                    btn.setContentDescription("Use fingerprint or face");
                    btn.setOnClickListener(v -> showBiometric());
                    bioButton = btn;
                    if (!canUseBiometric()) btn.setVisibility(View.INVISIBLE);
                    break;
                case "del":
                    btn.setText("⌫");
                    btn.setContentDescription("Delete");
                    btn.setOnClickListener(v -> {
                        if (entered.length() > 0) entered.deleteCharAt(entered.length() - 1);
                        renderDots();
                    });
                    btn.setOnLongClickListener(v -> { entered.setLength(0); renderDots(); return true; });
                    break;
                default:
                    btn.setText(k);
                    btn.setOnClickListener(v -> onDigit(k));
            }
            grid.addView(btn);
        }
        root.addView(grid);

        Button ok = new Button(this);
        ok.setText("Unlock");
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(dp(200), LinearLayout.LayoutParams.WRAP_CONTENT);
        okLp.topMargin = dp(16);
        ok.setLayoutParams(okLp);
        ok.setOnClickListener(v -> submit());
        root.addView(ok);

        Button cancel = new Button(this, null, android.R.attr.borderlessButtonStyle);
        cancel.setText(isSettingsMode() ? "Close" : "Exit");
        cancel.setOnClickListener(v -> cancel());
        root.addView(cancel);

        root.setBackgroundColor(resolveColor(android.R.attr.colorBackground));
        return root;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setGravity(Gravity.CENTER);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private void renderDots() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entered.length(); i++) sb.append('●');
        dots.setText(sb);
    }

    // ------------------------------------------------------------------ PIN logic

    private void onDigit(String d) {
        if (isLockedOut()) { updateLockoutMessage(); return; }
        if (entered.length() >= PIN_MAX) return;
        entered.append(d);
        renderDots();
        // Auto-submit once the length matches a correct PIN (no need to press Unlock).
        if (entered.length() >= 4 && prefs.checkPin(entered.toString())) success();
    }

    private void submit() {
        if (isLockedOut()) { updateLockoutMessage(); return; }
        if (entered.length() < 4) { message.setText("PIN is at least 4 digits"); return; }
        if (prefs.checkPin(entered.toString())) {
            success();
        } else {
            boolean locked = prefs.recordFail();
            entered.setLength(0);
            renderDots();
            if (locked) {
                updateLockoutMessage();
            } else {
                int left = Prefs.MAX_FAILS - prefs.fails();
                message.setText("Wrong PIN · " + left + " tries left");
            }
            dots.animate().translationX(dp(12)).setDuration(60).withEndAction(() ->
                    dots.animate().translationX(0).setDuration(60).start()).start();
        }
    }

    private boolean isLockedOut() {
        return System.currentTimeMillis() < prefs.lockoutUntil();
    }

    private void updateLockoutMessage() {
        if (message == null) return;
        long left = prefs.lockoutUntil() - System.currentTimeMillis();
        if (left > 0) {
            message.setText("Too many tries. Wait " + ((left / 1000) + 1) + " s");
            handler.postDelayed(this::updateLockoutMessage, 1000);
        } else if (message.getText().toString().startsWith("Too many")) {
            message.setText("Enter your PIN");
        }
    }

    protected void success() {
        prefs.resetFails();
        if (isSettingsMode()) {
            setResult(RESULT_OK);
        } else {
            GuardService.onUnlocked(pkg);
        }
        finish();
        overridePendingTransition(0, 0);
    }

    protected void cancel() {
        if (bioCancel != null) bioCancel.cancel();
        if (isSettingsMode()) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        GuardService.onLockCancelled();
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(home); } catch (Exception ignored) {}
        finish();
        overridePendingTransition(0, 0);
    }

    // ------------------------------------------------------------------ biometric

    @SuppressWarnings("deprecation")
    private boolean canUseBiometric() {
        if (!prefs.useBiometric()) return false;
        BiometricManager bm = getSystemService(BiometricManager.class);
        if (bm == null) return false;
        int r;
        if (Build.VERSION.SDK_INT >= 30) {
            r = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
        } else {
            r = bm.canAuthenticate();
        }
        return r == BiometricManager.BIOMETRIC_SUCCESS;
    }

    private void showBiometric() {
        if (!canUseBiometric() || isLockedOut() || isFinishing()) return;
        BiometricPrompt.Builder b = new BiometricPrompt.Builder(this)
                .setTitle("Verify it's you")
                .setConfirmationRequired(false)
                .setNegativeButton("Use PIN", getMainExecutor(), (d, w) -> {});
        if (Build.VERSION.SDK_INT >= 30) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK);
        }
        bioCancel = new CancellationSignal();
        try {
            b.build().authenticate(bioCancel, getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    success();
                }
            });
        } catch (Exception ex) {
            if (bioButton != null) bioButton.setVisibility(View.INVISIBLE);
        }
    }

    // ------------------------------------------------------------------ utils

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int resolveColor(int attr) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.resourceId != 0 ? getColor(tv.resourceId) : tv.data;
    }
}
