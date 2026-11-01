package io.github.smartagentictv.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/** Reads a quote when the idle screen starts, then every 15 minutes, if the voice is switched on. */
final class IdleVoice {
    private static final long EVERY_MS = 15 * 60_000L;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;

    private final Runnable speak = new Runnable() {
        @Override public void run() {
            if (!running) return;
            Voice.say(context, Quotes.now().text);
            handler.postDelayed(this, EVERY_MS);
        }
    };

    IdleVoice(Context c) {
        context = c.getApplicationContext();
    }

    void start() {
        if (running || !enabled(context)) return;
        running = true;
        handler.post(speak);
    }

    void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        Voice.quiet();
    }

    static boolean enabled(Context c) {
        return c.getSharedPreferences("glass", Context.MODE_PRIVATE).getBoolean("voice", true);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences("glass", Context.MODE_PRIVATE).edit().putBoolean("voice", on).apply();
    }
}
