package io.github.smartagentictv.phone;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns spoken words into TV commands by plain rules, in English, Hindi and Kannada, with no AI. If it isn't sure it
 * says so rather than guess: anything not understood does nothing. No Android classes, so it can be tested on a computer.
 */
final class VoiceCommands {
    private VoiceCommands() {}

    static final class Result {
        final List<String> commands = new ArrayList<>();
        String search;          // words to look for in the Shows tab
        boolean openTyping;     // go to the Type tab
        boolean understood() { return search != null || openTyping || !commands.isEmpty(); }
    }

    private static final int UP = 19, DOWN = 20, LEFT = 21, RIGHT = 22, OK = 23, HOME = 3, BACK = 4, VOLUME_UP = 24, VOLUME_DOWN = 25, PLAY_PAUSE = 85;
    private static final int VOLUME_STEPS = 5, MAX_REPEAT = 10;

    /** Each app's names as speech recognition writes them. Longest names are tried first, so "sun next" beats "sun". */
    private static final String[][] APP_NAMES = {
        {"in.startv.hotstar", "jiohotstar", "jio hotstar", "hotstar", "hot star", "जियोहॉटस्टार", "जियो हॉटस्टार", "हॉटस्टार", "ಜಿಯೋ ಹಾಟ್‌ಸ್ಟಾರ್", "ಜಿಯೋ ಹಾಟ್ ಸ್ಟಾರ್", "ಹಾಟ್‌ಸ್ಟಾರ್", "ಹಾಟ್ ಸ್ಟಾರ್", "ಹಾಟ್ಸ್ಟಾರ್"},
        {"com.netflix.mediaclient", "netflix", "नेटफ्लिक्स", "ನೆಟ್‌ಫ್ಲಿಕ್ಸ್", "ನೆಟ್ಫ್ಲಿಕ್ಸ್", "ನೆಟ್ ಫ್ಲಿಕ್ಸ್"},
        {"playboxtv.tv.android.in", "playbox tv", "playbox", "play box", "प्लेबॉक्स", "प्ले बॉक्स", "ಪ್ಲೇಬಾಕ್ಸ್", "ಪ್ಲೇ ಬಾಕ್ಸ್"},
        {"com.suntv.sunnxt", "sun nxt", "sun next", "sunnxt", "sunnext", "सन नेक्स्ट", "ಸನ್ ನೆಕ್ಸ್ಟ್", "ಸನ್ನೆಕ್ಸ್ಟ್"},
        {"com.google.android.youtube.tv", "youtube", "you tube", "यूट्यूब", "ಯೂಟ್ಯೂಬ್", "ಯುಟ್ಯೂಬ್"},
        {"com.graymatrix.did", "zee5", "zee 5", "zee five", "z5", "जी5", "जी 5", "जी फाइव", "जी फ़ाइव", "ಜೀ5", "ಜೀ 5", "ಜೀ ಫೈವ್"},
    };

    private static final String[] SEARCH_FRONT = {"search for ", "search ", "find ", "look for ", "play "};
    private static final String[] SEARCH_BACK = {" खोजो", " ढूंढो", " ढूँढो", " सर्च करो", " ಹುಡುಕು", " ಹುಡುಕಿ", " ಸರ್ಚ್ ಮಾಡು"};
    private static final String[] OPEN_FRONT = {"open ", "launch ", "start ", "go to "};
    private static final String[] OPEN_BACK = {" खोलो", " खोल दो", " चलाओ", " लगाओ", " ತೆರೆ", " ತೆರೆಯಿರಿ", " ತೆರೆಯಿ", " ಓಪನ್ ಮಾಡು", " ಓಪನ್ ಮಾಡಿ", " ಹಾಕು", " ಹಾಕಿ"};
    private static final Pattern AND = Pattern.compile("\\s+(?:and|then|और|फिर|ಮತ್ತು|ಆಮೇಲೆ)\\s+");
    private static final Pattern NUMBER = Pattern.compile("\\d{1,3}");

    private static final String[] VOLUME_WORDS = {"volume", "sound", "वॉल्यूम", "आवाज", "ಧ್ವನಿ", "ವಾಲ್ಯೂಮ್", "ಸೌಂಡ್"};
    private static final String[] MORE_WORDS = {"up", "increase", "raise", "more", "बढ़ा", "ज्यादा", "ಹೆಚ್ಚು", "ಹೆಚ್ಚಿ", "ಜಾಸ್ತಿ"};
    private static final String[] LESS_WORDS = {"down", "decrease", "lower", "reduce", "less", "कम", "घटा", "ಕಡಿಮೆ", "ಕಮ್ಮಿ"};

    /** Whole phrases for one key. */
    private static final Map<String, Integer> KEYS = new LinkedHashMap<>();
    static {
        for (String s : new String[] {"go home", "home", "home screen", "होम", "ಹೋಮ್"}) KEYS.put(s, HOME);
        for (String s : new String[] {"go back", "back", "वापस", "वापस जाओ", "पीछे", "पीछे जाओ", "ಹಿಂದೆ", "ಹಿಂದೆ ಹೋಗು", "ಬ್ಯಾಕ್"}) KEYS.put(s, BACK);
        for (String s : new String[] {"up", "go up", "ऊपर", "ऊपर जाओ", "ಮೇಲೆ", "ಮೇಲೆ ಹೋಗು"}) KEYS.put(s, UP);
        for (String s : new String[] {"down", "go down", "नीचे", "नीचे जाओ", "ಕೆಳಗೆ", "ಕೆಳಗೆ ಹೋಗು"}) KEYS.put(s, DOWN);
        for (String s : new String[] {"left", "go left", "बाएं", "बाएँ", "बाएं जाओ", "ಎಡ", "ಎಡಕ್ಕೆ"}) KEYS.put(s, LEFT);
        for (String s : new String[] {"right", "go right", "दाएं", "दाएँ", "दाएं जाओ", "ಬಲ", "ಬಲಕ್ಕೆ"}) KEYS.put(s, RIGHT);
        for (String s : new String[] {"ok", "okay", "select", "enter", "ओके", "ठीक है", "ಓಕೆ", "ಸರಿ"}) KEYS.put(s, OK);
        for (String s : new String[] {"play", "pause", "resume", "stop", "रोको", "चलाओ", "ಪ್ಲೇ", "ನಿಲ್ಲಿಸು", "ಪಾಸ್"}) KEYS.put(s, PLAY_PAUSE);
    }

    private static final String[] MUTE = {"mute", "mute the tv", "silence", "म्यूट", "म्यूट करो", "ಮ್ಯೂಟ್", "ಮ್ಯೂಟ್ ಮಾಡು"};
    private static final String[] UNMUTE = {"unmute", "unmute the tv", "अनम्यूट", "अनम्यूट करो", "आवाज चालू करो", "ಅನ್‌ಮ್ಯೂಟ್", "ಅನ್ಮ್ಯೂಟ್", "ಅನ್‌ಮ್ಯೂಟ್ ಮಾಡು"};
    private static final String[] SETTINGS = {"settings", "open settings", "सेटिंग्स", "सेटिंग्स खोलो", "सेटिंग", "ಸೆಟ್ಟಿಂಗ್ಸ್", "ಸೆಟ್ಟಿಂಗ್ಸ್ ತೆರೆ"};
    private static final String[] TYPING = {"keyboard", "open keyboard", "type", "कीबोर्ड", "कीबोर्ड खोलो", "ಕೀಬೋರ್ಡ್", "ಕೀಬೋರ್ಡ್ ತೆರೆ"};

    /** `installed` maps other apps' lowercase names to their packages, as the TV lists them. */
    static Result parse(String heard, Map<String, String> installed) {
        Result r = new Result();
        String text = clean(heard);
        if (text.isEmpty()) return r;
        // A title can hold "and", so a search is taken whole, before splitting into steps.
        String words = searchWords(text);
        if (words != null) {
            r.search = words;
            return r;
        }
        for (String step : AND.split(text)) {
            Result one = new Result();
            if (!parseOne(step.trim(), installed, one)) return new Result(); // all or nothing: half a command surprises
            r.commands.addAll(one.commands);
            r.openTyping |= one.openTyping;
        }
        return r;
    }

    private static String clean(String heard) {
        String s = Normalizer.normalize(heard == null ? "" : heard, Normalizer.Form.NFD).replace("़", ""); // ज़ and ज are the same word here
        s = Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        s = s.replace("‌", "").replace("‍", "");
        s = s.replaceAll("[.,!?।]", " ").replaceAll("\\s+", " ").trim();
        return s.replaceFirst("^please ", "").replaceFirst(" please$", "");
    }

    private static String searchWords(String text) {
        for (String front : SEARCH_FRONT) {
            if (text.startsWith(front) && text.length() > front.length()) {
                String rest = text.substring(front.length()).trim();
                return front.equals("play ") && !looksLikeTitle(rest) ? null : rest;
            }
        }
        for (String back : SEARCH_BACK) {
            if (text.endsWith(back) && text.length() > back.length()) return text.substring(0, text.length() - back.length()).trim();
        }
        return null;
    }

    private static boolean looksLikeTitle(String rest) {
        return !rest.equals("it") && !rest.equals("this") && !rest.equals("the video");
    }

    private static boolean parseOne(String step, Map<String, String> installed, Result r) {
        if (step.isEmpty()) return false;
        if (has(MUTE, clean(step))) return r.commands.add("mute 1");
        if (has(UNMUTE, clean(step))) return r.commands.add("mute 0");
        if (has(SETTINGS, clean(step))) return r.commands.add("settings");
        if (has(TYPING, clean(step))) {
            r.openTyping = true;
            return true;
        }
        if (volume(step, r)) return true;
        String app = appIn(step, installed);
        if (app != null) return r.commands.add("open " + app);
        return key(step, r);
    }

    /** "open youtube", "यूट्यूब खोलो", "ಯೂಟ್ಯೂಬ್ ತೆರೆ", or just the app's name. */
    private static String appIn(String step, Map<String, String> installed) {
        String name = step;
        for (String front : OPEN_FRONT) if (name.startsWith(front)) name = name.substring(front.length()).trim();
        for (String back : OPEN_BACK) if (name.endsWith(back)) name = name.substring(0, name.length() - back.length()).trim();
        for (String[] app : APP_NAMES) {
            for (int i = 1; i < app.length; i++) if (clean(app[i]).equals(name)) return app[0];
        }
        return installed == null ? null : installed.get(name);
    }

    private static boolean volume(String step, Result r) {
        boolean about = containsAny(step, VOLUME_WORDS);
        boolean louder = step.equals("louder") || step.equals("increase") || (about && containsWord(step, MORE_WORDS));
        boolean quieter = step.equals("quieter") || step.equals("softer") || (about && containsWord(step, LESS_WORDS));
        Matcher m = NUMBER.matcher(step);
        if (about && m.find() && !louder && !quieter) {
            int level = Integer.parseInt(m.group());
            if (level > 100) return false;
            r.commands.add("vol " + level);
            return true;
        }
        if (louder == quieter) return false; // neither, or both: not sure
        for (int i = 0; i < VOLUME_STEPS; i++) r.commands.add("key " + (louder ? VOLUME_UP : VOLUME_DOWN));
        return true;
    }

    /** A key's phrase, with an optional count: "down 3". */
    private static boolean key(String step, Result r) {
        int times = 1;
        String phrase = step;
        Matcher m = Pattern.compile("^(.*\\S)\\s+(\\d{1,2})$").matcher(step);
        if (m.matches()) {
            phrase = m.group(1);
            times = Math.min(MAX_REPEAT, Math.max(1, Integer.parseInt(m.group(2))));
        }
        Integer key = null;
        for (Map.Entry<String, Integer> e : KEYS.entrySet()) if (clean(e.getKey()).equals(phrase)) key = e.getValue();
        if (key == null) return false;
        for (int i = 0; i < times; i++) r.commands.add("key " + key);
        return true;
    }

    private static boolean has(String[] phrases, String text) {
        for (String p : phrases) if (clean(p).equals(text)) return true;
        return false;
    }

    private static boolean containsAny(String text, String[] words) {
        for (String w : words) if (text.contains(clean(w))) return true;
        return false;
    }

    /** Latin words must match whole ("up" isn't in "supper"); Hindi and Kannada words change their endings, so a part is enough. */
    private static boolean containsWord(String text, String[] words) {
        for (String w : words) {
            String c = clean(w);
            if (c.charAt(0) < 128 ? (" " + text + " ").contains(" " + c + " ") : text.contains(c)) return true;
        }
        return false;
    }
}
