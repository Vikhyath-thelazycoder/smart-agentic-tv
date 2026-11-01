package io.github.smartagentictv.tv;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * What differs between TV boards, from assets/boards/*.properties: the first file whose `match` app is installed, or
 * default.properties. docs/ADD-YOUR-BOARD.md explains how to write one. Nothing here is guessed for a board nobody has tested.
 */
final class Board {
    private static Board current;
    private final Properties p;

    private Board(Properties p) { this.p = p; }

    static synchronized Board get(Context c) {
        if (current == null) current = load(c);
        return current;
    }

    private static Board load(Context c) {
        Properties base = read(c, "default.properties");
        try {
            String[] files = c.getAssets().list("boards");
            if (files != null) {
                Arrays.sort(files);
                PackageManager pm = c.getPackageManager();
                for (String f : files) {
                    if (f.equals("default.properties")) continue;
                    Properties one = read(c, f);
                    String match = one.getProperty("match", "");
                    if (match.isEmpty()) continue;
                    try {
                        pm.getApplicationInfo(match, 0);
                        Properties merged = new Properties();
                        merged.putAll(base);
                        merged.putAll(one);
                        merged.setProperty("never_open", base.getProperty("never_open", "") + "," + one.getProperty("never_open", ""));
                        AppLog.w("board profile: " + f);
                        return new Board(merged);
                    } catch (PackageManager.NameNotFoundException notThisOne) {
                        // Next.
                    }
                }
            }
        } catch (IOException e) {
            AppLog.w("board profiles unreadable");
        }
        return new Board(base);
    }

    private static Properties read(Context c, String name) {
        Properties p = new Properties();
        try (InputStream in = c.getAssets().open("boards/" + name)) {
            p.load(in);
        } catch (IOException e) {
            AppLog.w("no board file " + name);
        }
        return p;
    }

    String oldHome() { return p.getProperty("old_home", "").trim(); }
    String mouse() { return p.getProperty("mouse_device", "").trim(); }
    String keyboard() { return p.getProperty("keyboard_device", "").trim(); }
    int sourceScanCode() {
        try {
            return Integer.parseInt(p.getProperty("source_scan_code", "0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    String settingsActivity() { return p.getProperty("settings_activity", "").trim(); }
    String miracastActivity() { return p.getProperty("miracast_activity", "").trim(); }

    Set<String> neverOpen() {
        Set<String> out = new HashSet<>();
        for (String s : p.getProperty("never_open", "").split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }
}
