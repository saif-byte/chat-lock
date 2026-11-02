package com.phonetools.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Event-driven, no polling, no wake-locks, no foreground service, no network.
 *
 * Speed design:
 *  - An instant full-screen cover (accessibility overlay, no extra permission) is drawn
 *    in the same frame a chat is detected, so chat content is never visible while the
 *    PIN screen starts up.
 *  - Tapping the Messages / inbox button shows the cover immediately, before the app
 *    has even drawn the inbox. If it turns out not to be a chat, the cover goes away.
 *  - Checks run at the first sign of a screen change (leading edge), not after a delay.
 *
 * Battery design:
 *  - Normally only "window changed" events are delivered (a few per minute).
 *  - Content-change and tap events are switched on only while Instagram or X is in front,
 *    checks are rate-limited, and they are switched off again when you leave.
 */
public class GuardService extends AccessibilityService {

    private static GuardService instance;

    /** Minimum time between two screen scans while Instagram/X is open. */
    private static final long MIN_CHECK_INTERVAL = 300;
    /** Short grace so brief in-chat popups (image viewer, menus) don't re-prompt. */
    private static final long MIN_GRACE_MS = 3000;
    private static final long LOCK_RELAUNCH_GUARD_MS = 1500;
    /** A predicted cover (from a tap) is removed after this if no chat appears. */
    private static final long PREDICTED_COVER_MS = 1200;
    /** Safety: never leave the cover up longer than this. */
    private static final long MAX_COVER_MS = 4000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private WindowManager wm;

    private String foregroundTarget;          // instagram / x package currently in front, or null
    private boolean liveEventsOn;
    private boolean checkPending;
    private String pendingPkg;
    private long lastCheckAt;

    private final Map<String, String> lastActivity = new HashMap<>();
    private final Map<String, Boolean> activityCache = new HashMap<>();
    /** pkg -> time we stopped being in its chats (0 = currently in chats). Present only while unlocked. */
    private final Map<String, Long> unlocked = new HashMap<>();
    private final Map<String, Boolean> lastDetected = new HashMap<>();
    private long lastLockLaunch;

    private View cover;
    private boolean coverPredicted;

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
    private final Runnable predictedCoverTimeout = () -> { if (coverPredicted) hideCover(); };
    private final Runnable coverSafetyTimeout = this::hideCover;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        prefs = new Prefs(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        loadPackageSets();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, f);
        }
        setLiveEvents(false);
    }

    @Override
    public void onDestroy() {
        hideCover();
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

        switch (type) {
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
                // Tapped the inbox / Messages button: cover the screen right now.
                if (!isUnlocked(pkg) && ChatDetector.isInboxTap(pkg, e)) {
                    showCover(true);
                    requestCheck(pkg, true);
                }
                break;
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                // A chat Activity opened (e.g. X DMs, notification tap): lock without scanning.
                if (!isUnlocked(pkg) && ChatDetector.isChatActivity(pkg, lastActivity.get(pkg))) {
                    showLock(pkg);
                }
                requestCheck(pkg, true);
                break;
            default:
                requestCheck(pkg, false);
        }
    }

    private void onWindowChanged(String pkg, CharSequence cls) {
        long now = SystemClock.elapsedRealtime();
        if (ChatDetector.isTarget(pkg)) {
            if (cls != null && isRealActivity(pkg, cls.toString())) {
                lastActivity.put(pkg, cls.toString());
            }
            if (!pkg.equals(foregroundTarget)) {
                foregroundTarget = pkg;
                setLiveEvents(prefs.isTargetEnabled(pkg) && prefs.hasPin());
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
            hideCover();
            setLiveEvents(false);
        }
    }

    /** Leading-edge throttle: scan right away, then at most every MIN_CHECK_INTERVAL. */
    private void requestCheck(String pkg, boolean urgent) {
        pendingPkg = pkg;
        long now = SystemClock.elapsedRealtime();
        long wait = Math.max(0, lastCheckAt + MIN_CHECK_INTERVAL - now);
        if (urgent) {
            handler.removeCallbacks(checkRunnable);
            wait = 0;
        } else if (checkPending) {
            return;
        }
        checkPending = true;
        if (wait == 0) handler.postAtFrontOfQueue(checkRunnable);
        else handler.postDelayed(checkRunnable, wait);
    }

    private void runCheck() {
        checkPending = false;
        lastCheckAt = SystemClock.elapsedRealtime();
        String pkg = pendingPkg;
        if (pkg == null || !pkg.equals(foregroundTarget)) return;

        AccessibilityNodeInfo root = findAppRoot(pkg);
        if (root == null) return;
        try {
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
                        hideCover();
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

    /** Root of the app's own window (the instant cover can be the "active" window). */
    private AccessibilityNodeInfo findAppRoot(String pkg) {
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null && pkg.equals(r.getPackageName().toString())) return r;
            ChatDetector.recycle(r);
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo wr = w.getRoot();
                if (wr != null && wr.getPackageName() != null && pkg.equals(wr.getPackageName().toString())) return wr;
                ChatDetector.recycle(wr);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean isUnlocked(String pkg) {
        Long leftAt = unlocked.get(pkg);
        if (leftAt == null) return false;
        if (leftAt == 0L) return true;
        long grace = Math.max(MIN_GRACE_MS, prefs.relockDelayMs());
        return SystemClock.elapsedRealtime() - leftAt <= grace;
    }

    private void showLock(String pkg) {
        showCover(false);                          // same frame: hide the chat instantly
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

    // ------------------------------------------------------------------ instant cover

    private void showCover(boolean predicted) {
        if (wm == null) return;
        if (cover != null) {
            if (!predicted) {                      // a real detection upgrades a predicted cover
                coverPredicted = false;
                handler.removeCallbacks(predictedCoverTimeout);
            }
            return;
        }
        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        FrameLayout v = new FrameLayout(this);
        v.setBackgroundColor(dark ? 0xFF121212 : 0xFFFAFAFA);
        TextView icon = new TextView(this);
        icon.setText("🔒");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        v.addView(icon, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        v.setOnTouchListener((view, ev) -> true); // swallow touches so nothing behind can be used

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        lp.windowAnimations = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            wm.addView(v, lp);
            cover = v;
            coverPredicted = predicted;
            if (predicted) handler.postDelayed(predictedCoverTimeout, PREDICTED_COVER_MS);
            handler.postDelayed(coverSafetyTimeout, MAX_COVER_MS);
        } catch (Exception ex) {
            cover = null;
        }
    }

    private void hideCover() {
        handler.removeCallbacks(predictedCoverTimeout);
        handler.removeCallbacks(coverSafetyTimeout);
        if (cover != null && wm != null) {
            try { wm.removeViewImmediate(cover); } catch (Exception ignored) {}
        }
        cover = null;
        coverPredicted = false;
    }

    // ------------------------------------------------------------------ called by LockActivity

    /** The PIN screen is drawn, so the instant cover is no longer needed. */
    static void onLockScreenVisible() {
        GuardService s = instance;
        if (s != null) s.hideCover();
    }

    static void onUnlocked(String pkg) {
        GuardService s = instance;
        if (s != null && pkg != null) {
            s.unlocked.put(pkg, 0L);
            s.lastLockLaunch = 0;
            s.hideCover();
        }
    }

    static void onLockCancelled() {
        GuardService s = instance;
        if (s != null) {
            s.lastLockLaunch = 0;
            s.hideCover();
            s.performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    static boolean isRunning() {
        return instance != null;
    }

    /** Settings changed (toggle on/off, new PIN): re-evaluate event subscription. */
    static void refresh() {
        GuardService s = instance;
        if (s != null && s.foregroundTarget == null) s.setLiveEvents(false);
    }

    // ------------------------------------------------------------------ helpers

    private void setLiveEvents(boolean on) {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        int wanted = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | (on ? AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                        | AccessibilityEvent.TYPE_VIEW_CLICKED : 0);
        long timeout = on ? 100 : 300;
        if (info.eventTypes != wanted || info.notificationTimeout != timeout) {
            info.eventTypes = wanted;
            info.notificationTimeout = timeout;
            setServiceInfo(info);
        }
        liveEventsOn = on;
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
        String app = ChatDetector.appName(pkg);
        String msg = inChat ? "Test: " + app + " chats detected (" + reason + ")"
                : "Test: " + app + " – not a chat screen";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
