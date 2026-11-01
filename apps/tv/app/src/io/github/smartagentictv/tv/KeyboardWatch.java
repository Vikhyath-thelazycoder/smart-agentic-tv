package io.github.smartagentictv.tv;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Tells when an app is showing its own letter grid instead of Android's keyboard (Zee5 does), so the phone can open
 * its keyboard then too. Looks only when the phone asks, counts single characters on screen, skips password fields,
 * and keeps nothing: the answer is only yes or no.
 */
public class KeyboardWatch extends AccessibilityService {
    private static final int MAX_NODES = 500, LETTERS_FOR_A_GRID = 20;
    private static volatile KeyboardWatch running;

    @Override protected void onServiceConnected() { running = this; }

    @Override public boolean onUnbind(android.content.Intent intent) {
        running = null;
        return super.onUnbind(intent);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}

    /** A line on the TV over whatever is playing, for a timer or reminder. False if this service isn't running. */
    static boolean note(String text, long showMs) {
        KeyboardWatch w = running;
        if (w == null) return false;
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(() -> {
            android.widget.TextView line = new android.widget.TextView(w);
            line.setText(text);
            line.setTextColor(0xFFFFFFFF);
            line.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 28);
            line.setPadding(44, 22, 44, 24);
            android.graphics.drawable.GradientDrawable glass = new android.graphics.drawable.GradientDrawable();
            glass.setColor(0xE6202028);
            glass.setCornerRadius(38);
            glass.setStroke(2, 0x66FFFFFF);
            line.setBackground(glass);
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(-2, -2,
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
            lp.y = 40;
            android.view.WindowManager windows = (android.view.WindowManager) w.getSystemService(WINDOW_SERVICE);
            try {
                windows.addView(line, lp);
                main.postDelayed(() -> {
                    try { windows.removeView(line); } catch (RuntimeException ignored) {}
                }, showMs);
            } catch (RuntimeException e) {
                AppLog.w("note: " + e.getMessage());
            }
        });
        return true;
    }

    /** The app on screen, or "" if unknown. */
    static String topPackage() {
        KeyboardWatch w = running;
        AccessibilityNodeInfo root = w == null ? null : w.getRootInActiveWindow();
        if (root == null) return "";
        CharSequence pkg = root.getPackageName();
        root.recycle();
        return pkg == null ? "" : pkg.toString();
    }

    /**
     * Some apps with their own grid ignore typed keys (Sun NXT), so the text goes straight into their text box.
     * Adds `add`, or removes the last character when it's null. False if there's no such box, or it's a password
     * box, whose real text can't be read back: those keep using keys.
     */
    static boolean editField(String add) { return editField(add, false); }

    /** Puts `text` in the box in place of whatever is there. */
    static boolean replaceField(String text) { return editField(text, true); }

    private static boolean editField(String add, boolean replace) {
        KeyboardWatch w = running;
        AccessibilityNodeInfo root = w == null ? null : w.getRootInActiveWindow();
        if (root == null) return false;
        ArrayDeque<AccessibilityNodeInfo> todo = new ArrayDeque<>();
        todo.add(root);
        int seen = 0;
        boolean done = false;
        while (!todo.isEmpty()) {
            AccessibilityNodeInfo n = todo.poll();
            if (!done && seen++ < MAX_NODES) {
                if (n.isEditable() && n.isVisibleToUser()) {
                    done = !n.isPassword() && setText(n, add, replace);
                    if (!done) {
                        n.recycle();
                        break;
                    }
                }
                for (int i = 0; !done && i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo child = n.getChild(i);
                    if (child != null) todo.add(child);
                }
            }
            n.recycle();
        }
        for (AccessibilityNodeInfo left : todo) left.recycle();
        return done;
    }

    private static boolean setText(AccessibilityNodeInfo field, String add, boolean replace) {
        CharSequence shown = field.getText();
        String now = shown == null || field.isShowingHintText() ? "" : shown.toString();
        String next = replace ? add : add != null ? now + add : now.isEmpty() ? now : now.substring(0, now.length() - 1);
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next);
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    static boolean letterGridOpen() {
        KeyboardWatch w = running;
        AccessibilityNodeInfo root = w == null ? null : w.getRootInActiveWindow();
        if (root == null) return false;
        Set<Character> letters = new HashSet<>();
        ArrayDeque<AccessibilityNodeInfo> todo = new ArrayDeque<>();
        todo.add(root);
        int seen = 0;
        boolean found = false;
        while (!todo.isEmpty()) {
            AccessibilityNodeInfo n = todo.poll();
            if (!found && seen++ < MAX_NODES && n != null) {
                if (!n.isPassword()) {
                    CharSequence t = n.getText();
                    if (t != null && t.length() == 1 && Character.isLetter(t.charAt(0))) letters.add(Character.toLowerCase(t.charAt(0)));
                    found = letters.size() >= LETTERS_FOR_A_GRID;
                }
                for (int i = 0; !found && i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo child = n.getChild(i);
                    if (child != null) todo.add(child);
                }
            }
            if (n != null) n.recycle();
        }
        return found;
    }
}
