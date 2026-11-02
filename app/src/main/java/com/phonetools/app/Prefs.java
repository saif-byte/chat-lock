package com.phonetools.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** All settings live in private app storage on this phone only. The PIN is stored as a salted PBKDF2 hash. */
public final class Prefs {
    private static final String FILE = "prefs";
    private static final int ITERATIONS = 12000;
    public static final int MAX_FAILS = 5;
    public static final long LOCKOUT_MS = 30_000L;

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---------- PIN ----------
    public boolean hasPin() {
        return sp.contains("pin_hash");
    }

    public void setPin(String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        sp.edit()
                .putString("pin_salt", toHex(salt))
                .putString("pin_hash", toHex(hash(pin, salt)))
                .putInt("fails", 0)
                .putLong("lockout_until", 0)
                .apply();
    }

    public boolean checkPin(String pin) {
        String s = sp.getString("pin_salt", null);
        String h = sp.getString("pin_hash", null);
        if (s == null || h == null) return false;
        byte[] actual = hash(pin, fromHex(s));
        return MessageDigest.isEqual(actual, fromHex(h));
    }

    private static byte[] hash(String pin, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- brute-force protection ----------
    public int fails() { return sp.getInt("fails", 0); }

    public long lockoutUntil() { return sp.getLong("lockout_until", 0); }

    /** Records a wrong PIN; returns true if this triggered a lockout. */
    public boolean recordFail() {
        int f = fails() + 1;
        SharedPreferences.Editor e = sp.edit();
        boolean locked = false;
        if (f >= MAX_FAILS) {
            e.putLong("lockout_until", System.currentTimeMillis() + LOCKOUT_MS);
            f = 0;
            locked = true;
        }
        e.putInt("fails", f).apply();
        return locked;
    }

    public void resetFails() {
        sp.edit().putInt("fails", 0).putLong("lockout_until", 0).apply();
    }

    // ---------- options ----------
    public boolean lockInstagram() { return sp.getBoolean("lock_ig", true); }
    public void setLockInstagram(boolean v) { sp.edit().putBoolean("lock_ig", v).apply(); }

    public boolean lockX() { return sp.getBoolean("lock_x", true); }
    public void setLockX(boolean v) { sp.edit().putBoolean("lock_x", v).apply(); }

    public boolean lockSnapchat() { return sp.getBoolean("lock_snap", true); }
    public void setLockSnapchat(boolean v) { sp.edit().putBoolean("lock_snap", v).apply(); }

    public boolean useBiometric() { return sp.getBoolean("biometric", true); }
    public void setUseBiometric(boolean v) { sp.edit().putBoolean("biometric", v).apply(); }

    public boolean diagnostics() { return sp.getBoolean("diag", false); }
    public void setDiagnostics(boolean v) { sp.edit().putBoolean("diag", v).apply(); }

    /** How long you can be away from your chats before the lock comes back. */
    public long relockDelayMs() { return sp.getLong("relock_ms", 0L); }
    public void setRelockDelayMs(long v) { sp.edit().putLong("relock_ms", v).apply(); }

    public String extraMarkersRaw() { return sp.getString("extra_markers", ""); }
    public void setExtraMarkersRaw(String v) { sp.edit().putString("extra_markers", v).apply(); }

    public List<String> extraMarkers() {
        List<String> out = new ArrayList<>();
        for (String s : extraMarkersRaw().split(",")) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (t.length() >= 3) out.add(t);
        }
        return out;
    }

    public boolean isTargetEnabled(String pkg) {
        if (ChatDetector.INSTAGRAM.equals(pkg)) return lockInstagram();
        if (ChatDetector.X.equals(pkg)) return lockX();
        if (ChatDetector.SNAPCHAT.equals(pkg)) return lockSnapchat();
        return false;
    }

    // ---------- hex helpers ----------
    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    private static byte[] fromHex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
