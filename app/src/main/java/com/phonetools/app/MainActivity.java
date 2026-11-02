package com.phonetools.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_AUTH = 7;
    private static final long REAUTH_AFTER_MS = 60_000;

    private Prefs prefs;
    private TextView serviceStatus, pinStatus, restrictedHint;
    private Button serviceButton, pinButton, delayButton;

    private boolean authed;
    private boolean waitingForAuth;
    private long stoppedAt;

    private static final long[] DELAYS = {0, 30_000, 60_000, 300_000};
    private static final String[] DELAY_LABELS = {"Immediately", "After 30 seconds", "After 1 minute", "After 5 minutes"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        prefs = new Prefs(this);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Protect the settings themselves with the same PIN.
        if (prefs.hasPin() && !waitingForAuth) {
            boolean expired = stoppedAt != 0 && SystemClock.elapsedRealtime() - stoppedAt > REAUTH_AFTER_MS;
            if (!authed || expired) {
                authed = false;
                waitingForAuth = true;
                startActivityForResult(new Intent(this, SettingsLockActivity.class), REQ_AUTH);
            }
        }
        refreshStatus();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!waitingForAuth) stoppedAt = SystemClock.elapsedRealtime();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_AUTH) {
            waitingForAuth = false;
            if (res == RESULT_OK) {
                authed = true;
                stoppedAt = 0;
            } else {
                finish();
            }
        }
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        col.setPadding(p, p, p, dp(40));
        scroll.addView(col);

        col.addView(header(getString(R.string.app_name), 26));
        TextView sub = body("Locks only the chats/DMs inside Instagram, X and Snapchat. The rest of each app stays open.");
        col.addView(sub);

        // Step 1: PIN
        col.addView(section("1 · Set your PIN"));
        pinStatus = body("");
        col.addView(pinStatus);
        pinButton = button("Set PIN", v -> showPinDialog());
        col.addView(pinButton);

        // Step 2: accessibility service
        col.addView(section("2 · Turn on the lock service"));
        serviceStatus = body("");
        col.addView(serviceStatus);
        serviceButton = button("Open Accessibility settings", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                Toast.makeText(this, "Tap “" + getString(R.string.service_label) + "” (may be under Downloaded apps) and switch it on", Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {}
        });
        col.addView(serviceButton);
        restrictedHint = body("If the switch is greyed out (“Restricted setting”): open App info below → tap ⋮ (top-right) → “Allow restricted settings”, then try again.");
        restrictedHint.setAlpha(0.7f);
        col.addView(restrictedHint);
        col.addView(button("Open App info", v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        }));

        // Step 3: options
        col.addView(section("3 · What to lock"));
        col.addView(toggle("Lock Instagram chats", prefs.lockInstagram(), (c, on) -> { prefs.setLockInstagram(on); GuardService.refresh(); }));
        col.addView(toggle("Lock X chats", prefs.lockX(), (c, on) -> { prefs.setLockX(on); GuardService.refresh(); }));
        col.addView(toggle("Lock Snapchat chats", prefs.lockSnapchat(), (c, on) -> { prefs.setLockSnapchat(on); GuardService.refresh(); }));
        col.addView(toggle("Allow fingerprint / face unlock", prefs.useBiometric(), (c, on) -> prefs.setUseBiometric(on)));

        col.addView(body("Lock again after leaving chats:"));
        delayButton = button(delayLabel(), v -> chooseDelay());
        col.addView(delayButton);
        col.addView(body("Chats always lock again when the screen turns off."));

        // Advanced
        col.addView(section("If detection stops working"));
        col.addView(body("Instagram, X and Snapchat change their apps often. Turn on test messages to see what the app detects as you move around the apps."));
        col.addView(toggle("Show detection test messages", prefs.diagnostics(), (c, on) -> prefs.setDiagnostics(on)));
        col.addView(body("Extra words that mean “this is a chat screen” (comma-separated, matches view IDs or on-screen labels):"));
        EditText markers = new EditText(this);
        markers.setSingleLine(true);
        markers.setText(prefs.extraMarkersRaw());
        markers.setHint("e.g. thread_view, chat_list");
        col.addView(markers);
        col.addView(button("Save extra words", v -> {
            prefs.setExtraMarkersRaw(markers.getText().toString());
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        }));

        // Privacy
        col.addView(section("Privacy"));
        col.addView(body("• This app has no internet permission, so it cannot send anything anywhere.\n"
                + "• It never reads or saves your messages; it only checks which screen is open.\n"
                + "• Your PIN is stored as a salted hash on this phone only. No backups, no analytics, no ads."));
        return scroll;
    }

    private void refreshStatus() {
        boolean on = isServiceEnabled();
        serviceStatus.setText(on ? "✅ Lock service is ON" : "⚠️ Lock service is OFF – chats are not protected yet");
        serviceButton.setText(on ? "Accessibility settings" : "Turn on in Accessibility settings");
        restrictedHint.setVisibility(on || Build.VERSION.SDK_INT < 33 ? View.GONE : View.VISIBLE);
        pinStatus.setText(prefs.hasPin() ? "✅ PIN is set" : "⚠️ No PIN yet – set one first");
        pinButton.setText(prefs.hasPin() ? "Change PIN" : "Set PIN");
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(this, GuardService.class);
        TextUtils.SimpleStringSplitter sp = new TextUtils.SimpleStringSplitter(':');
        sp.setString(enabled);
        while (sp.hasNext()) {
            ComponentName cn = ComponentName.unflattenFromString(sp.next());
            if (me.equals(cn)) return true;
        }
        return false;
    }

    private void showPinDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText pin1 = pinField("New PIN (4–8 digits)");
        EditText pin2 = pinField("Repeat new PIN");
        box.addView(pin1);
        box.addView(pin2);

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(prefs.hasPin() ? "Change PIN" : "Set PIN")
                .setView(box)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String a = pin1.getText().toString(), b2 = pin2.getText().toString();
            if (a.length() < 4) { pin1.setError("At least 4 digits"); return; }
            if (!a.equals(b2)) { pin2.setError("PINs don't match"); return; }
            prefs.setPin(a);
            authed = true;
            GuardService.refresh();
            refreshStatus();
            Toast.makeText(this, "PIN saved", Toast.LENGTH_SHORT).show();
            d.dismiss();
        }));
        d.show();
    }

    private void chooseDelay() {
        int cur = 0;
        for (int i = 0; i < DELAYS.length; i++) if (DELAYS[i] == prefs.relockDelayMs()) cur = i;
        new AlertDialog.Builder(this)
                .setTitle("Lock again after leaving chats")
                .setSingleChoiceItems(DELAY_LABELS, cur, (d, which) -> {
                    prefs.setRelockDelayMs(DELAYS[which]);
                    delayButton.setText(delayLabel());
                    d.dismiss();
                })
                .show();
    }

    private String delayLabel() {
        for (int i = 0; i < DELAYS.length; i++) if (DELAYS[i] == prefs.relockDelayMs()) return DELAY_LABELS[i];
        return DELAY_LABELS[0];
    }

    // ------------------------------------------------------------------ small view helpers

    private EditText pinField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        return e;
    }

    private TextView header(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView section(String s) {
        TextView t = header(s, 18);
        t.setPadding(0, dp(24), 0, dp(6));
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setOnClickListener(l);
        return btn;
    }

    private Switch toggle(String s, boolean checked, CompoundButton.OnCheckedChangeListener l) {
        Switch sw = new Switch(this);
        sw.setText(s);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sw.setChecked(checked);
        sw.setPadding(0, dp(10), 0, dp(10));
        sw.setOnCheckedChangeListener(l);
        return sw;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
