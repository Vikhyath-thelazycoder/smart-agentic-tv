package io.github.smartagentictv.tv;

/** Checks Jarvis's plain text rules on a computer. Run by tools/jarvis-test.sh. */
public final class JarvisTest {
    private static int failed;

    public static void main(String[] a) {
        same("Kantara", "Kantara Karana movie", true);
        same("Kantara", "kantaara", true);
        same("Kantara", "kantara film", true);
        same("Kantara: Chapter 1", "kantara", false);
        same("The Office", "the office", true);
        same("Jailer", "jailer tamil movie", true);
        same("Lupin", "kantara", false);
        same("Sholay", "shole", false); // too far to open unasked: the closest matches are shown instead
        same("Tom and Jerry", "tom and jerry show", true);
        check("related by a whole word", Words.related("Kantara", "Kantara Karana"));
        check("unrelated", !Words.related("Spider-Man", "Kantara Karana"));
        check("fillers gone", Words.withoutFillers("Kantara Karana movie").equals("kantara karana"));
        check("never empty", Words.withoutFillers("movie").equals("movie"));
        check("single words", Words.singleWords("Kantara Karana movie", 3).contains("kantara"));
        for (String y : new String[] {"Yes", "yes.", "OK", "go ahead", "हाँ", "ठीक है", "ಹೌದು", "ಸರಿ"}) check("yes: " + y, Words.isYes(y));
        for (String n : new String[] {"No", "cancel", "don't", "नहीं", "ಬೇಡ", "ಇಲ್ಲ"}) check("no: " + n, Words.isNo(n));
        for (String n : new String[] {"yes turn on the lights", "maybe", "play kantara", "okay then what"}) check("not a clear yes: " + n, !Words.isYes(n));
        for (String s : new String[] {"what's on my screen", "who is this actor", "स्क्रीन पर क्या है", "ಸ್ಕ್ರೀನ್ ಮೇಲೆ ಏನಿದೆ"}) check("about the screen: " + s, Words.aboutScreen(s));
        check("not about the screen", !Words.aboutScreen("what is the capital of france"));
        System.out.println(failed == 0 ? "All passed" : failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void same(String title, String asked, boolean want) { check(title + " / " + asked, Words.sameTitle(title, asked) == want); }

    private static void check(String name, boolean ok) {
        if (!ok) {
            failed++;
            System.out.println("FAILED: " + name);
        }
    }
}
