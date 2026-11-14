package com.phonetools.app;

import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether the screen currently shown by Instagram / X / Snapchat is a chat (DM) screen.
 * It looks at three cheap signals, in order:
 *   1. the name of the current Activity (e.g. X opens DMs in their own Activity),
 *   2. internal view IDs of the visible screen (e.g. Instagram's thread composer),
 *   3. a few unambiguous hint/label texts (e.g. "Start a message").
 * Nothing is stored or sent anywhere; the result is just true/false.
 */
public final class ChatDetector {
    public static final String INSTAGRAM = "com.instagram.android";
    public static final String X = "com.twitter.android";
    public static final String SNAPCHAT = "com.snapchat.android";

    public static boolean isTarget(String pkg) {
        return INSTAGRAM.equals(pkg) || X.equals(pkg) || SNAPCHAT.equals(pkg);
    }

    public static String appName(String pkg) {
        if (INSTAGRAM.equals(pkg)) return "Instagram";
        if (X.equals(pkg)) return "X";
        if (SNAPCHAT.equals(pkg)) return "Snapchat";
        return pkg;
    }

    /** Upper bound on nodes inspected per check. Big enough for Snapchat's deep screens. */
    private static final int MAX_NODES = 3000;

    private static final String[] IG_IDS = {
            "row_thread_composer",
            "thread_composer",
            "direct_thread",
            "direct_inbox",
            "inbox_refreshable_thread_list",
            "thread_list_recyclerview",
            "direct_message_list",
            "message_list",
    };
    // Instagram: view ids that contain "direct" but are NOT chats (share sheet, home button).
    private static final String[] IG_ID_EXCLUDE = {
            "direct_private_share",
            "direct_share",
            "action_bar_inbox",
            "direct_tab",
    };
    private static final String[] IG_HINTS = {"message...", "message…"};

    // X: view-id prefixes / fragments used by DM screens.
    private static final String[] X_ID_PREFIX = {"dm_", "conversation_", "chat_", "xchat_"};
    private static final String[] X_ID_CONTAINS = {"_dm_", "dm_inbox", "dm_composer", "message_compose"};
    private static final String[] X_TEXTS = {
            "start a message",
            "start a new message",
            "search direct messages",
            "search messages",
            "search chats",
            "start a new chat",
            "unencrypted message",
            "encrypted message",
            "message requests",
            "new message",
    };

    // Snapchat: open conversation.
    private static final String[] SNAP_IDS = {
            "chat_input",
            "chat_message",
            "chat_messages",
            "chat_fragment",
            "chat_list",
            "chat_conversation",
            "friends_feed",
            "ff_item",
            "ff_list",
            "feed_new_chat",
    };
    private static final String[] SNAP_ID_EXCLUDE = {"ngs_", "chat_icon", "chat_tab", "chat_badge"};
    private static final String[] SNAP_TEXTS = {"send a chat", "start a new chat", "chat with"};
    // Snapchat: status words shown on every row of the Chat list (friends feed).
    private static final String[] SNAP_FEED_STATUS = {
            "new chat", "new snap", "new chats", "new snaps", "received", "opened", "delivered",
            "sent", "replayed", "screenshotted", "tap to chat", "tap to view", "typing",
            "missed call", "called", "saved in chat", "pending",
    };

    private ChatDetector() {}

    /** Returns a short reason string if this is a chat screen, otherwise null. */
    public static String detect(String pkg, String activityClass, AccessibilityNodeInfo root,
                                List<String> extraMarkers) {
        String byClass = classSignal(pkg, activityClass);
        if (byClass != null) return byClass;
        if (root == null) return null;

        boolean snap = SNAPCHAT.equals(pkg);
        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        int topZone = rootBounds.top + Math.max(1, rootBounds.height()) / 5;   // top 20% of the window
        Rect r = new Rect();
        int feedRows = 0;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        String found = null;
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.poll();
            // Skip anything off screen (e.g. Snapchat keeps neighbouring pages in memory).
            if (found == null && seen < MAX_NODES && (n == root || n.isVisibleToUser())) {
                seen++;
                found = nodeSignal(pkg, n, extraMarkers);
                if (found == null && snap) {
                    found = snapSignal(n, topZone, r);
                    if (found == null && isSnapFeedRow(n) && ++feedRows >= 2) found = "chat list";
                }
                if (found == null) {
                    int c = n.getChildCount();
                    for (int i = 0; i < c; i++) {
                        AccessibilityNodeInfo ch = n.getChild(i);
                        if (ch != null) queue.add(ch);
                    }
                }
            }
            if (n != root) recycle(n);
        }
        return found;
    }

    /** Snapchat-only signals that need position or state. */
    private static String snapSignal(AccessibilityNodeInfo n, int topZone, Rect r) {
        String text = lower(n.getText());
        String desc = lower(n.getContentDescription());
        String hint = lower(hintOf(n));
        // Message box of an open conversation.
        if (n.isEditable()) {
            for (String t : new String[]{hint, text, desc}) {
                if (t != null && (t.equals("chat") || t.startsWith("send a chat") || t.startsWith("chat "))) {
                    return "message box";
                }
            }
        }
        // "Chat" page title at the top of the screen (the tab label sits at the bottom instead).
        String label = text != null ? text : desc;
        if (label != null && (label.equals("chat") || label.equals("chats"))) {
            n.getBoundsInScreen(r);
            if (r.bottom <= topZone && r.height() > 0) return "chat page title";
        }
        // Bottom bar with the Chat tab selected.
        String id = n.getViewIdResourceName();
        if (n.isSelected() && ((id != null && id.toLowerCase(Locale.ROOT).contains("chat"))
                || "chat".equals(desc) || "chats".equals(desc))) {
            return "chat tab selected";
        }
        return null;
    }

    /** A row of Snapchat's Chat list, e.g. "Ali, New Chat · 2m" or "Opened · 1d". */
    private static boolean isSnapFeedRow(AccessibilityNodeInfo n) {
        String t = firstNonNull(lower(n.getText()), lower(n.getContentDescription()));
        if (t == null || t.length() > 120) return false;
        for (String part : t.split("[·•,|\\n]")) {
            String p = part.trim();
            for (String st : SNAP_FEED_STATUS) {
                if (p.equals(st) || p.startsWith(st + " ")) return true;
            }
        }
        return false;
    }

    // Buttons that open the inbox (tapped from the feed / tab bar).
    private static final String[] IG_TAP_IDS = {"action_bar_inbox_button", "direct_tab", "inbox_button", "direct_inbox"};
    private static final String[] IG_TAP_TEXTS = {"messenger", "direct", "message", "messages", "direct messages"};
    private static final String[] X_TAP_IDS = {"dms", "dm_tab", "messages_tab", "chat_tab"};
    private static final String[] SNAP_TAP_IDS = {"ngs_chat_icon", "ngs_chat", "chat_tab"};
    private static final String[] SNAP_TAP_TEXTS = {"chat", "chats", "chat tab"};
    private static final String[] X_TAP_TEXTS = {"messages", "direct messages", "chat", "chats", "message"};

    /**
     * Predicts a chat screen from a tap, before the new screen is even drawn.
     * Only used to show an instant cover; the real check still decides.
     */
    public static boolean isInboxTap(String pkg, AccessibilityEvent e) {
        boolean ig = INSTAGRAM.equals(pkg), x = X.equals(pkg), snap = SNAPCHAT.equals(pkg);
        if (!ig && !x && !snap) return false;
        String[] texts = ig ? IG_TAP_TEXTS : (x ? X_TAP_TEXTS : SNAP_TAP_TEXTS);
        String[] ids = ig ? IG_TAP_IDS : (x ? X_TAP_IDS : SNAP_TAP_IDS);
        if (matchesLabel(lower(e.getContentDescription()), texts)) return true;
        List<CharSequence> t = e.getText();
        if (t != null && t.size() == 1 && matchesLabel(lower(t.get(0)), texts)) return true;

        AccessibilityNodeInfo src = null;
        try { src = e.getSource(); } catch (Exception ignored) {}
        if (src == null) return false;
        try {
            String id = src.getViewIdResourceName();
            if (id != null) {
                String name = id.substring(id.indexOf(":id/") + 4).toLowerCase(Locale.ROOT);
                for (String m : ids) {
                    if (name.equals(m) || name.contains(m)) return true;
                }
            }
            return matchesLabel(lower(src.getContentDescription()), texts);
        } finally {
            recycle(src);
        }
    }

    /** Label equals a marker, or starts with it followed by a separator (e.g. "Messages, 2 unread"). */
    private static boolean matchesLabel(String s, String[] markers) {
        if (s == null || s.length() > 40) return false;
        for (String m : markers) {
            if (s.equals(m)) return true;
            if (s.startsWith(m) && s.length() > m.length()) {
                char c = s.charAt(m.length());
                if (c == ',' || c == ' ' || c == '(' || c == '.') return true;
            }
        }
        return false;
    }

    /** Cheap check using only the activity name (no screen scan). */
    public static boolean isChatActivity(String pkg, String cls) {
        return classSignal(pkg, cls) != null;
    }

    private static String classSignal(String pkg, String cls) {
        if (cls == null) return null;
        String lower = cls.toLowerCase(Locale.ROOT);
        String simple = lower.substring(lower.lastIndexOf('.') + 1);
        if (INSTAGRAM.equals(pkg)) {
            if (lower.contains(".direct.") || simple.startsWith("direct")) return "activity " + cls;
        } else if (SNAPCHAT.equals(pkg)) {
            if (simple.startsWith("chat") || simple.contains("conversation")) return "activity " + cls;
        } else if (X.equals(pkg)) {
            for (String seg : lower.split("\\.")) {
                if (seg.equals("dm") || seg.equals("dms") || seg.equals("xchat") || seg.equals("chat")) {
                    return "activity " + cls;
                }
            }
            if (simple.startsWith("dm") || simple.contains("conversation") || simple.contains("chat")) {
                return "activity " + cls;
            }
        }
        return null;
    }

    private static String nodeSignal(String pkg, AccessibilityNodeInfo n, List<String> extra) {
        String id = n.getViewIdResourceName();
        String idName = null;
        if (id != null) {
            int p = id.indexOf(":id/");
            idName = (p >= 0 ? id.substring(p + 4) : id).toLowerCase(Locale.ROOT);
        }

        if (INSTAGRAM.equals(pkg)) {
            if (idName != null && !startsWithAny(idName, IG_ID_EXCLUDE) && !containsAny(idName, IG_ID_EXCLUDE)) {
                for (String m : IG_IDS) if (idName.contains(m)) return "id " + idName;
            }
            String hint = lower(hintOf(n));
            if (hint != null) for (String h : IG_HINTS) if (hint.equals(h)) return "hint " + hint;
            String txt = lower(n.getText());
            if (txt != null && n.isEditable()) for (String h : IG_HINTS) if (txt.equals(h)) return "hint " + txt;
        } else if (X.equals(pkg)) {
            if (idName != null) {
                if (startsWithAny(idName, X_ID_PREFIX) || containsAny(idName, X_ID_CONTAINS)) return "id " + idName;
            }
            String t = firstNonNull(lower(hintOf(n)), lower(n.getText()), lower(n.getContentDescription()));
            if (t != null && t.length() < 60) {
                for (String m : X_TEXTS) if (t.equals(m) || t.startsWith(m)) return "text " + t;
            }
        }

        if (SNAPCHAT.equals(pkg)) {
            if (idName != null && !containsAny(idName, SNAP_ID_EXCLUDE)) {
                for (String m : SNAP_IDS) if (idName.contains(m)) return "id " + idName;
            }
            String t = firstNonNull(lower(hintOf(n)), lower(n.getText()), lower(n.getContentDescription()));
            if (t != null && t.length() < 40) {
                for (String m : SNAP_TEXTS) if (t.equals(m) || t.startsWith(m + " ")) return "text " + t;
            }
        }

        if (!extra.isEmpty()) {
            String t = firstNonNull(lower(n.getText()), lower(n.getContentDescription()), lower(hintOf(n)));
            for (String m : extra) {
                if (idName != null && idName.contains(m)) return "custom id " + idName;
                if (t != null && t.contains(m)) return "custom text " + m;
            }
        }
        return null;
    }

    private static CharSequence hintOf(AccessibilityNodeInfo n) {
        return n.getHintText();
    }

    private static String lower(CharSequence cs) {
        if (cs == null) return null;
        String s = cs.toString().trim();
        return s.isEmpty() ? null : s.toLowerCase(Locale.ROOT);
    }

    private static String firstNonNull(String... s) {
        for (String x : s) if (x != null) return x;
        return null;
    }

    private static boolean startsWithAny(String s, String[] arr) {
        for (String a : arr) if (s.startsWith(a)) return true;
        return false;
    }

    private static boolean containsAny(String s, String[] arr) {
        for (String a : arr) if (s.contains(a)) return true;
        return false;
    }

    @SuppressWarnings("deprecation")
    static void recycle(AccessibilityNodeInfo n) {
        if (n != null && Build.VERSION.SDK_INT < 33) {
            try { n.recycle(); } catch (Exception ignored) {}
        }
    }
}
