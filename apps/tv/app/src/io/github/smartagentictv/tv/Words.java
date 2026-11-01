package io.github.smartagentictv.tv;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Plain text rules for Jarvis and the title search. No Android classes, so tools/JarvisTest.java checks them on a computer. */
final class Words {
    private Words() {}

    private static final Set<String> FILLERS = new HashSet<>(Arrays.asList("movie", "movies", "film", "picture", "cinema", "show", "series",
            "serial", "web", "tv", "season", "episode", "full", "hd", "watch", "play", "latest", "new", "kannada", "hindi", "tamil", "telugu", "malayalam"));
    private static final Set<String> YES = new HashSet<>(Arrays.asList("yes", "yeah", "yep", "yes please", "ok", "okay", "sure", "do it", "go ahead", "confirm",
            "haan", "han", "ha", "theek hai", "हाँ", "हां", "हा", "ठीक है", "कर दो", "ಹೌದು", "ಸರಿ", "ಮಾಡು", "ಓಕೆ"));
    private static final Set<String> NO = new HashSet<>(Arrays.asList("no", "nope", "cancel", "dont", "don t", "do not", "stop", "never mind", "nahi", "nahin",
            "नहीं", "मत करो", "रहने दो", "ಬೇಡ", "ಇಲ್ಲ"));
    private static final String[] SCREEN = {"screen", "showing", "watching", "on tv", "on the tv", "this", "that", "who is", "scene", "picture",
            "स्क्रीन", "दिख", "टीवी पर", "ಸ್ಕ್ರೀನ್", "ಪರದೆ", "ತೋರ", "ಟಿವಿಯಲ್ಲಿ"};

    static String normal(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return n.replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ").trim();
    }

    /** The words that could be part of a title. Never empties the text. */
    static String withoutFillers(String s) {
        StringBuilder out = new StringBuilder();
        for (String w : normal(s).split(" ")) {
            if (!FILLERS.contains(w)) out.append(out.length() == 0 ? "" : " ").append(w);
        }
        return out.length() == 0 ? normal(s) : out.toString();
    }

    /** The words worth searching alone, longest first. */
    static List<String> singleWords(String s, int most) {
        List<String> out = new ArrayList<>();
        for (String w : withoutFillers(s).split(" ")) if (w.length() >= 3 && !out.contains(w)) out.add(w);
        out.sort((a, b) -> b.length() - a.length());
        return out.size() > most ? out.subList(0, most) : out;
    }

    /** 1 for the same text, 0 for nothing alike. */
    static double alike(String a, String b) {
        String x = normal(a), y = normal(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        int[] row = new int[y.length() + 1];
        for (int j = 0; j < row.length; j++) row[j] = j;
        for (int i = 1; i <= x.length(); i++) {
            int corner = row[0];
            row[0] = i;
            for (int j = 1; j <= y.length(); j++) {
                int up = row[j];
                row[j] = Math.min(Math.min(up, row[j - 1]) + 1, corner + (x.charAt(i - 1) == y.charAt(j - 1) ? 0 : 1));
                corner = up;
            }
        }
        return 1 - row[y.length()] / (double) Math.max(x.length(), y.length());
    }

    /** The title shares a whole word with what was asked, or is nearly the same text. */
    static boolean related(String title, String asked) {
        if (alike(title, asked) >= 0.6) return true;
        Set<String> words = new HashSet<>(Arrays.asList(normal(title).split(" ")));
        for (String w : withoutFillers(asked).split(" ")) if (w.length() >= 3 && words.contains(w)) return true;
        return false;
    }

    /** Sure enough to open it without asking: "kantara karana" is Kantara, and "kantaara" is too. */
    static boolean sameTitle(String title, String asked) {
        String t = normal(title), a = withoutFillers(asked);
        if (t.isEmpty() || a.isEmpty()) return false;
        if (alike(t, a) >= 0.78) return true;
        return t.length() >= 4 && (" " + a + " ").contains(" " + t + " ");
    }

    static boolean isYes(String s) { return YES.contains(normal(s)); }

    static boolean isNo(String s) { return NO.contains(normal(s)); }

    /** They spoke about what's on the screen, so sending a picture of it is what they asked for. */
    static boolean aboutScreen(String s) {
        String n = normal(s);
        for (String w : SCREEN) if (n.contains(w)) return true;
        return false;
    }
}
