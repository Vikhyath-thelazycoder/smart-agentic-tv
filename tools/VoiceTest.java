package io.github.smartagentictv.phone;

import java.util.HashMap;
import java.util.Map;

/** Checks the voice rules on a computer: tools/voice-test.sh. Each case is what's heard, then what must happen. */
public final class VoiceTest {
    private static int failed;

    public static void main(String[] a) {
        String yt = "open com.google.android.youtube.tv", hot = "open in.startv.hotstar", nf = "open com.netflix.mediaclient",
                zee = "open com.graymatrix.did", sun = "open com.suntv.sunnxt", box = "open playboxtv.tv.android.in";
        String up5 = "key 24,key 24,key 24,key 24,key 24", down5 = "key 25,key 25,key 25,key 25,key 25";
        // English
        check("open YouTube", yt); check("Open youtube.", yt); check("please open hotstar", hot); check("open jio hotstar", hot);
        check("launch Netflix", nf); check("go to zee5", zee); check("open zee 5", zee); check("open sun next", sun); check("open sun nxt", sun);
        check("open playbox", box); check("youtube", yt); check("open vlc", "open org.videolan.vlc");
        check("volume up", up5); check("volume down", down5); check("increase the volume", up5); check("lower the volume", down5);
        check("louder", up5); check("quieter", down5); check("set volume to 30", "vol 30"); check("volume 45", "vol 45"); check("volume 300", "");
        check("mute", "mute 1"); check("unmute", "mute 0"); check("go home", "key 3"); check("go back", "key 4"); check("back", "key 4");
        check("up", "key 19"); check("down 3", "key 20,key 20,key 20"); check("left", "key 21"); check("right", "key 22"); check("OK", "key 23"); check("select", "key 23");
        check("pause", "key 85"); check("play", "key 85"); check("settings", "settings"); check("open settings", "settings");
        check("open youtube and volume up", yt + "," + up5); check("open netflix then mute", nf + ",mute 1");
        search("search kantara", "kantara"); search("search for the office", "the office"); search("find lupin", "lupin");
        search("play ayyana mane", "ayyana mane"); search("search tom and jerry", "tom and jerry");
        // Hindi
        check("यूट्यूब खोलो", yt); check("हॉटस्टार खोलो", hot); check("जियो हॉटस्टार खोलो", hot); check("नेटफ्लिक्स चलाओ", nf); check("जी 5 खोलो", zee); check("सन नेक्स्ट खोलो", sun);
        check("आवाज़ बढ़ाओ", up5); check("आवाज बढ़ाओ", up5); check("आवाज़ कम करो", down5); check("वॉल्यूम बढ़ाओ", up5); check("आवाज़ 30", "vol 30");
        check("म्यूट करो", "mute 1"); check("अनम्यूट करो", "mute 0"); check("होम", "key 3"); check("वापस जाओ", "key 4"); check("ऊपर", "key 19"); check("नीचे", "key 20"); check("बाएं", "key 21"); check("दाएं", "key 22");
        check("ओके", "key 23"); check("रोको", "key 85"); check("यूट्यूब खोलो और आवाज़ बढ़ाओ", yt + "," + up5);
        search("कांतारा खोजो", "कांतारा");
        // Kannada
        check("ಯೂಟ್ಯೂಬ್ ತೆರೆ", yt); check("ಹಾಟ್‌ಸ್ಟಾರ್ ತೆರೆಯಿರಿ", hot); check("ನೆಟ್‌ಫ್ಲಿಕ್ಸ್ ಹಾಕು", nf); check("ಜೀ 5 ತೆರೆ", zee); check("ಸನ್ ನೆಕ್ಸ್ಟ್ ಓಪನ್ ಮಾಡು", sun);
        check("ಧ್ವನಿ ಹೆಚ್ಚಿಸು", up5); check("ಧ್ವನಿ ಕಡಿಮೆ ಮಾಡು", down5); check("ಸೌಂಡ್ ಜಾಸ್ತಿ ಮಾಡು", up5); check("ವಾಲ್ಯೂಮ್ ಕಡಿಮೆ", down5);
        check("ಮ್ಯೂಟ್ ಮಾಡು", "mute 1"); check("ಹಿಂದೆ", "key 4"); check("ಮೇಲೆ", "key 19"); check("ಕೆಳಗೆ", "key 20"); check("ಎಡ", "key 21"); check("ಬಲ", "key 22"); check("ಸರಿ", "key 23"); check("ನಿಲ್ಲಿಸು", "key 85");
        check("ಯೂಟ್ಯೂಬ್ ತೆರೆ ಮತ್ತು ಧ್ವನಿ ಕಡಿಮೆ ಮಾಡು", yt + "," + down5);
        search("ಕಾಂತಾರ ಹುಡುಕು", "ಕಾಂತಾರ");
        // Must do nothing rather than guess
        for (String s : new String[] {"", "   ", "hello there", "what is the weather", "open", "open the door", "open youtube and make me a sandwich", "volume", "supper", "turn it", "नमस्ते", "ನಮಸ್ಕಾರ", "volume up and down down down"}) check(s, "");
        VoiceCommands.Result t = VoiceCommands.parse("open keyboard", apps());
        report("open keyboard", t.openTyping ? "typing" : "not typing", "typing");
        System.out.println(failed == 0 ? "ALL PASSED" : failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static Map<String, String> apps() {
        Map<String, String> m = new HashMap<>();
        m.put("vlc", "org.videolan.vlc");
        return m;
    }

    private static void check(String heard, String want) {
        report(heard, String.join(",", VoiceCommands.parse(heard, apps()).commands), want);
    }

    private static void search(String heard, String want) {
        report(heard, String.valueOf(VoiceCommands.parse(heard, apps()).search), want);
    }

    private static void report(String heard, String got, String want) {
        if (got.equals(want)) return;
        failed++;
        System.out.println("FAIL \"" + heard + "\"\n  got  " + got + "\n  want " + want);
    }
}
