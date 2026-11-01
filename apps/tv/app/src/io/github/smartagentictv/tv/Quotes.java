package io.github.smartagentictv.tv;

import java.util.Locale;

/** The idle screen's lines: English only, on screen and spoken. */
final class Quotes {
    private Quotes() {}

    static final class Line {
        final String text;
        final Locale locale = Locale.ENGLISH;
        Line(String text) {
            this.text = text;
        }
    }

    private static final Line[] LINES = {
        new Line("Small steps every day become a long journey."),
        new Line("Every new day is a fresh start."),
        new Line("Be calm. Good things take time."),
        new Line("Breathe. Today is a good day to begin."),
        new Line("Patience is the key to every door."),
        new Line("Do one small thing well today."),
        new Line("Rest is part of the work."),
        new Line("Slow progress is still progress."),
        new Line("Be kind. It always comes back."),
    };

    /** A new line every 5 minutes. */
    static Line now() {
        long n = System.currentTimeMillis() / (5 * 60_000L);
        return LINES[(int) (n % LINES.length)];
    }
}
