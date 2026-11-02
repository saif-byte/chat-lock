package com.chatlock.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Event-driven, no polling, no wake-locks, no foreground service, no network.
 *
 * Battery design:
 *  - Normally only "window changed" events are delivered (a few per minute).
 *  - Only while Instagram or X is in front do we also ask for content-changed events,
 *    and those are throttled (one check at most every ~700 ms).
 *  - As soon as you leave Instagram/X, content events are switched off again.
 */
public class ChatLockService extends AccessibilityService {

    private static ChatLockService instance;

    private static final long STATE_CHECK_DELAY = 80;
    private static final long CONTENT_CHECK_DELAY = 700;
    /** Short grace so brief in-chat popups (image viewer, menus) don't re-prompt. */
    private static final long MIN_GRACE_MS = 3000;
    private static final long LOCK_RELAUNCH_GUARD_MS = 1500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Prefs prefs;

    private String foregroundTarget;          // instagram / x package currently in front, or null
    private boolean contentEventsOn;
    private boolean checkPending;
    private String pendingPkg;

    private final Map<String, String> lastActivity = new HashMap<>();
    private final Map<String, Boolean> activityCache = new HashMap<>();
    /** pkg -> time we stopped being in its chats (0 = currently in chats). Present only while unlocked. */
    private final Map<String, Long> unlocked = new HashMap<>();
    private final Map<String, Boolean> lastDetected = new HashMap<>();
    private long lastLockLaunch;

    private final Set<String> homePackages = new HashSet<>();
    private final Set<String> imePackages = new HashSet<>();
    private final Map<String, Boolean> launchableCache = new HashMap<>();

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // Screen off: lock everything again.
            unlocked.clear();
        }
    };

    private final Runnable checkRunnable = this::runCheck;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        prefs = new Prefs(this);
        loadPackageSets();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, f);
        }
        setContentEvents(false);
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {}

    // ------------------------------------------------------------------ events

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null || prefs == null) return;
        String pkg = e.getPackageName().toString();
        int type = e.getEventType();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            onWindowChanged(pkg, e.getClassName());
        }

        if (!pkg.equals(foregroundTarget)) return;
        if (!prefs.hasPin() || !prefs.isTargetEnabled(pkg)) return;

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handler.removeCallbacks(checkRunnable);
            scheduleCheck(pkg, STATE_CHECK_DELAY);
        } else if (!checkPending) {
            scheduleCheck(pkg, CONTENT_CHECK_DELAY); // throttle, never starve
        }
    }

    private void onWindowChanged(String pkg, CharSequence cls) {
        long now = SystemClock.elapsedRealtime();
        if (ChatDetector.INSTAGRAM.equals(pkg) || ChatDetector.X.equals(pkg)) {
            if (cls != null && isRealActivity(pkg, cls.toString())) {
                lastActivity.put(pkg, cls.toString());
            }
            if (!pkg.equals(foregroundTarget)) {
                foregroundTarget = pkg;
                setContentEvents(prefs.isTargetEnabled(pkg) && prefs.hasPin());
            }
            return;
        }
        if (foregroundTarget != null && isLeavingApp(pkg)) {
            // You switched to the home screen or another app.
            Long t = unlocked.get(foregroundTarget);
            if (t != null && t == 0L) unlocked.put(foregroundTarget, now);
            lastDetected.remove(foregroundTarget);
            foregroundTarget = null;
            handler.removeCallbacks(checkRunnable);
            checkPending = false;
            setContentEvents(false);
        }
    }

    private void scheduleCheck(String pkg, long delay) {
        pendingPkg = pkg;
        checkPending = true;
        handler.postDelayed(checkRunnable, delay);
    }

    private void runCheck() {
        checkPending = false;
        String pkg = pendingPkg;
        if (pkg == null || !pkg.equals(foregroundTarget)) return;

        AccessibilityNodeInfo root;
        try { root = getRootInActiveWindow(); } catch (Exception ex) { return; }
        if (root == null) return;
        try {
            if (root.getPackageName() == null || !pkg.equals(root.getPackageName().toString())) return;
            String reason = ChatDetector.detect(pkg, lastActivity.get(pkg), root, prefs.extraMarkers());
            boolean inChat = reason != null;
            diagnostics(pkg, inChat, reason);

            long now = SystemClock.elapsedRealtime();
            Long leftAt = unlocked.get(pkg);
            if (inChat) {
                if (leftAt != null) {
                    long grace = Math.max(MIN_GRACE_MS, prefs.relockDelayMs());
                    if (leftAt == 0L || now - leftAt <= grace) {
                        unlocked.put(pkg, 0L);     // still within unlocked session
                        return;
                    }
                    unlocked.remove(pkg);          // session expired
                }
                showLock(pkg);
            } else if (leftAt != null && leftAt == 0L) {
                unlocked.put(pkg, now);            // left chats, start grace timer
            }
        } finally {
            ChatDetector.recycle(root);
        }
    }

    private void showLock(String pkg) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLockLaunch < LOCK_RELAUNCH_GUARD_MS) return;
        lastLockLaunch = now;
        Intent i = new Intent(this, LockActivity.class)
                .putExtra(LockActivity.EXTRA_PKG, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        startActivity(i);
    }

    // ------------------------------------------------------------------ called by LockActivity

    static void onUnlocked(String pkg) {
        ChatLockService s = instance;
        if (s != null && pkg != null) {
            s.unlocked.put(pkg, 0L);
            s.lastLockLaunch = 0;
        }
    }

    static void onLockCancelled() {
        ChatLockService s = instance;
        if (s != null) {
            s.lastLockLaunch = 0;
            s.performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    static boolean isRunning() {
        return instance != null;
    }

    /** Settings changed (toggle on/off, new PIN): re-evaluate event subscription. */
    static void refresh() {
        ChatLockService s = instance;
        if (s != null && s.foregroundTarget == null) s.setContentEvents(false);
    }

    // ------------------------------------------------------------------ helpers

    private void setContentEvents(boolean on) {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        int wanted = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | (on ? AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED : 0);
        if (info.eventTypes != wanted) {
            info.eventTypes = wanted;
            setServiceInfo(info);
        }
        contentEventsOn = on;
    }

    private boolean isRealActivity(String pkg, String cls) {
        Boolean cached = activityCache.get(cls);
        if (cached != null) return cached;
        boolean ok;
        try {
            getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0);
            ok = true;
        } catch (PackageManager.NameNotFoundException ex) {
            ok = false;
        }
        activityCache.put(cls, ok);
        return ok;
    }

    /** True if a window from this package means the user left Instagram/X. */
    private boolean isLeavingApp(String pkg) {
        if (pkg.equals(getPackageName())) return false;          // our own lock screen
        if (pkg.equals("com.android.systemui")) return false;    // notification shade, biometric dialog
        if (pkg.equals("android")) return false;                 // system dialogs
        if (imePackages.contains(pkg)) return false;             // keyboard
        if (homePackages.contains(pkg)) return true;             // launcher
        Boolean c = launchableCache.get(pkg);
        if (c == null) {
            c = getPackageManager().getLaunchIntentForPackage(pkg) != null;
            launchableCache.put(pkg, c);
        }
        return c;                                                // another real app
    }

    private void loadPackageSets() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> list = getPackageManager().queryIntentActivities(home, 0);
            for (ResolveInfo ri : list) homePackages.add(ri.activityInfo.packageName);
        } catch (Exception ignored) {}
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                for (InputMethodInfo imi : imm.getInputMethodList()) imePackages.add(imi.getPackageName());
            }
        } catch (Exception ignored) {}
    }

    private void diagnostics(String pkg, boolean inChat, String reason) {
        if (!prefs.diagnostics()) return;
        Boolean prev = lastDetected.get(pkg);
        if (prev != null && prev == inChat) return;
        lastDetected.put(pkg, inChat);
        String app = ChatDetector.INSTAGRAM.equals(pkg) ? "Instagram" : "X";
        String msg = inChat ? "Chat Lock: " + app + " chats detected (" + reason + ")"
                : "Chat Lock: " + app + " – not a chat screen";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
