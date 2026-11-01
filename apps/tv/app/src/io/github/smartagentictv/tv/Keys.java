package io.github.smartagentictv.tv;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * The owner's API keys: pasted in the phone app and kept in this app's private storage on the TV, or built in from
 * config.properties. Several processes read them, so the file is read again whenever it has changed. Values never
 * leave the TV: the phone only hears which ones are set.
 */
final class Keys {
    private Keys() {}

    static final Set<String> NAMES = new HashSet<>(Arrays.asList("gemini", "groq", "tmdb", "watchmode", "region"));
    private static final String FILE = "api-keys.properties";
    private static File file;
    private static long readAt = -1;
    private static Properties values = new Properties();

    static synchronized void init(Context c) { file = new File(c.getFilesDir(), FILE); }

    static String gemini() { return value("gemini", Config.GEMINI_KEY); }
    static String groq() { return value("groq", Config.GROQ_KEY); }
    static String tmdb() { return value("tmdb", Config.TMDB_KEY); }
    static String watchmode() { return value("watchmode", Config.WATCHMODE_KEY); }

    /** Two letters: chosen on the phone, or built in, or the TV's own country. */
    static String region() {
        String r = value("region", Config.TMDB_REGION);
        if (r.isEmpty()) r = Locale.getDefault().getCountry();
        return r.isEmpty() ? "US" : r.toUpperCase(Locale.ROOT);
    }

    private static synchronized String value(String name, String builtIn) {
        reload();
        String v = values.getProperty(name, "").trim();
        return v.isEmpty() ? builtIn.trim() : v;
    }

    private static void reload() {
        if (file == null) return;
        long at = file.exists() ? file.lastModified() : 0;
        if (at == readAt) return;
        readAt = at;
        Properties p = new Properties();
        if (at != 0) {
            try (FileInputStream in = new FileInputStream(file)) {
                p.load(in);
            } catch (IOException e) {
                AppLog.w("api keys unreadable");
            }
        }
        values = p;
    }

    /** An empty value removes it. */
    static synchronized boolean set(String name, String value) {
        if (file == null || !NAMES.contains(name)) return false;
        readAt = -1;
        reload();
        if (value.trim().isEmpty()) values.remove(name);
        else values.setProperty(name, value.trim());
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            values.store(out, null);
            out.getFD().sync();
        } catch (IOException e) {
            return false;
        }
        readAt = -1;
        return tmp.renameTo(file);
    }

    /** Which are set, never what they are. */
    static String status() {
        return "gemini=" + (gemini().isEmpty() ? 0 : 1) + ",groq=" + (groq().isEmpty() ? 0 : 1) + ",tmdb=" + (tmdb().isEmpty() ? 0 : 1)
                + ",watchmode=" + (watchmode().isEmpty() ? 0 : 1) + ",region=" + region();
    }
}
