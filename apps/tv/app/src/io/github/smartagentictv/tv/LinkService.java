package io.github.smartagentictv.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.text.TextUtils;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.smartagentictv.wire.Wire;

/**
 * The TV's end of the phone link: answers the phone's search, pairs with a code, and presses keys through KeyHelper.
 * It also keeps ADB's network port firewalled when the lock is on: ADB itself has to keep running or KeyHelper dies,
 * and on this board it has no login check and always listens on the network, so the firewall is what closes it.
 */
public class LinkService extends Service implements Wire.Authority {
    private static final String CHANNEL = "link", LOCK_FILE = "adb-lock", PHONES_FILE = "phones.properties";
    private static final int NOTE = 7, MAX_CLIENTS = 4, CODE_MS = 120_000, MAX_TRIES = 3, ADB_PORT = 5555;
    /** Only these keys can be pressed from a phone: moving around, volume and media. */
    private static final Set<Integer> KEYS = new HashSet<>(Arrays.asList(3, 4, 19, 20, 21, 22, 23, 24, 25, 26, 61, 66, 67, 82, 178, 85, 89, 90, 126, 127, 164));
    private static final SecureRandom RANDOM = new SecureRandom();
    /** These come first in the phone's list of apps, in this order. */
    private static final String[] APPS_FIRST = {"in.startv.hotstar", "com.netflix.mediaclient", "playboxtv.tv.android.in",
            "com.suntv.sunnxt", "com.google.android.youtube.tv", "com.graymatrix.did"};
    private static final int MAX_TYPED = 500, KEY_DELETE = 67, KEY_OK = 23, KEY_VOLUME_UP = 24, KEY_VOLUME_DOWN = 25, KEY_MUTE = 164, APK_PART = 32 * 1024;
    private static final String AI_RULES = "You are the voice assistant of a home TV. Always reply in plain English, in one to three short sentences that sound natural when spoken aloud. No lists and no markdown.";
    private static final long APP_START_MS = 22_000, KEY_GAP_MS = 1500, SEARCH_OPEN_MS = 3500, RETRY_MS = 14_000;
    /** The remote keys from each app's home to its search, found on this TV. */
    private static final java.util.Map<String, int[]> SEARCH_KEYS = new java.util.HashMap<>();
    static {
        SEARCH_KEYS.put("in.startv.hotstar", new int[] {21, 19, 23, 22, 22, 19, 22, 23});
        SEARCH_KEYS.put("com.graymatrix.did", new int[] {21, 20, 23});
        SEARCH_KEYS.put("com.suntv.sunnxt", new int[] {21, 21, 21, 19, 23});
        SEARCH_KEYS.put("com.google.android.youtube.tv", new int[] {21, 19, 23});
    }
    private final java.util.concurrent.atomic.AtomicBoolean searching = new java.util.concurrent.atomic.AtomicBoolean();
    private static final long GRID_REMEMBER_MS = 3000, DUCK_MAX_MS = 45_000;
    private static final int DUCK_DIVIDE = 4, PICTURE_WAIT_MS = 10_000, HELPER_WAIT_MS = 3000;
    private volatile long gridSeenUntil;
    private static final String YOUTUBE = "com.google.android.youtube.tv", WATCH = "io.github.smartagentictv.tv/.KeyboardWatch";

    private static volatile LinkService running;

    private final ExecutorService setup = Executors.newSingleThreadExecutor();
    private final Object phonesLock = new Object();
    private Titles titles;
    private Ai ai;
    private Jarvis jarvis;
    private AppDownload download;
    private final Object duckLock = new Object();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private int duckedFrom = -1, duckedTo = -1;
    private Thread duckOwner;
    private final Runnable duckEnd = () -> duck(false);
    private final Object helperLock = new Object();
    private final java.util.concurrent.atomic.AtomicBoolean preparing = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean stopped;
    private volatile String netState = "checking";
    private ServerSocket server;
    private DatagramSocket search;
    private int clients;

    private String code;
    private long codeUntil;
    private int tries;

    private LocalSocket helper;
    private BufferedReader helperIn;
    private OutputStream helperOut;

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, LinkService.class));
        } catch (RuntimeException e) {
            AppLog.w("phone link didn't start: " + e.getMessage());
        }
    }

    static LinkService get() { return running; }

    @Override public void onCreate() {
        super.onCreate();
        titles = new Titles(this); // not earlier: a service has no storage until here
        ai = new Ai(this);
        jarvis = new Jarvis(this, titles, ai);
        NotificationManager n = getSystemService(NotificationManager.class);
        n.createNotificationChannel(new NotificationChannel(CHANNEL, "Phone link", NotificationManager.IMPORTANCE_MIN));
        startForeground(NOTE, new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.icon)
                .setContentTitle("Phone link").build());
        running = this;
        thread(this::listen);
        thread(this::answerSearches);
        download = new AppDownload(this);
        thread(download::run);
        setup.execute(this::prepare);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        stopped = true;
        running = null;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        if (search != null) search.close();
        if (download != null) download.stop();
        setup.shutdownNow();
        super.onDestroy();
    }

    private static void thread(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
    }

    // MARK: - Network

    private void listen() {
        while (!stopped) {
            try (ServerSocket s = new ServerSocket(Wire.PORT)) {
                server = s;
                while (!stopped) {
                    Socket client = s.accept();
                    synchronized (this) {
                        if (clients >= MAX_CLIENTS) {
                            client.close();
                            continue;
                        }
                        clients++;
                    }
                    thread(() -> serve(client));
                }
            } catch (IOException e) {
                if (!stopped) AppLog.w("phone link port: " + e.getMessage());
                pause(3000);
            }
        }
    }

    private void serve(Socket socket) {
        try (Socket s = socket) {
            s.setTcpNoDelay(true);
            s.setSoTimeout(10_000);
            Wire.Channel channel = Wire.accept(s, this);
            if (channel == null) return;
            s.setSoTimeout(30_000); // the phone says something at least every 5 seconds
            while (!stopped) channel.send(safely(channel.receive()));
        } catch (IOException e) {
            // The phone went away, or failed its proof. It looks for the TV again by itself.
        } finally {
            synchronized (duckLock) {
                if (duckOwner == Thread.currentThread()) duck(false); // the phone went away while the TV was turned down
            }
            synchronized (this) { clients--; }
        }
    }

    private void answerSearches() {
        byte[] ask = Wire.ASK.getBytes(StandardCharsets.US_ASCII);
        while (!stopped) {
            try (DatagramSocket s = new DatagramSocket(Wire.DISCOVERY_PORT)) {
                search = s;
                byte[] buffer = new byte[32];
                while (!stopped) {
                    DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                    s.receive(p);
                    if (p.getLength() != ask.length || !Arrays.equals(Arrays.copyOf(buffer, ask.length), ask)) continue;
                    byte[] reply = (Wire.ANSWER + Build.MODEL).getBytes(StandardCharsets.UTF_8);
                    s.send(new DatagramPacket(reply, reply.length, p.getSocketAddress()));
                }
            } catch (IOException e) {
                if (!stopped) AppLog.w("phone search port: " + e.getMessage());
                pause(3000);
            }
        }
    }

    /** An app that refuses to open, or anything else unexpected, is an answer to the phone: it must never end the link. */
    private String safely(String message) {
        try {
            return handle(message);
        } catch (RuntimeException e) {
            AppLog.w("command failed: " + e);
            return "err the TV couldn't do that";
        }
    }

    /** One command from a paired phone. Only these exist: nothing here can run anything else on the TV. */
    private String handle(String message) {
        if ("ping".equals(message)) return "pong";
        if ("info".equals(message)) return "info " + Build.MODEL + "|" + netState + "|" + (helperAlive() ? "ready" : "starting");
        if ("lock on".equals(message) || "lock off".equals(message)) {
            setLocked("lock on".equals(message));
            return "ok";
        }
        if (message.startsWith("mouse ")) {
            String[] p = message.split(" ");
            if (p.length != 3) return "err bad mouse";
            try {
                return helperCall("m " + clamp(p[1], 300) + " " + clamp(p[2], 300)) ? "ok" : "err keys aren't ready yet";
            } catch (NumberFormatException e) {
                return "err bad mouse";
            }
        }
        if ("click".equals(message)) return helperCall("c") ? "ok" : "err keys aren't ready yet";
        if (message.startsWith("scroll ")) {
            try {
                return helperCall("s " + clamp(message.substring(7).trim(), 20)) ? "ok" : "err keys aren't ready yet";
            } catch (NumberFormatException e) {
                return "err bad scroll";
            }
        }
        if (message.startsWith("type ")) {
            String text = message.substring(5);
            if (text.length() > MAX_TYPED) return "err too much text at once";
            if (usesOwnGrid() && KeyboardWatch.editField(text)) return "ok";
            // Android's virtual keyboard only has English keys, so other scripts go straight into the text box.
            if (!isPlainEnglish(text) && KeyboardWatch.editField(text)) return "ok";
            String coded = Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            return helperCall("t " + coded) ? "ok" : "err the TV can only type English letters, numbers and symbols";
        }
        if (message.startsWith("vol ")) {
            try {
                return setVolume(Integer.parseInt(message.substring(4).trim()));
            } catch (NumberFormatException e) {
                return "err bad volume";
            }
        }
        if ("mute 1".equals(message) || "mute 0".equals(message)) {
            AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, message.endsWith("1") ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
            return "ok";
        }
        if ("kb".equals(message)) return keyboardOpen() ? "kb 1" : "kb 0";
        if (message.startsWith("look ")) {
            String[] p = message.split(" ", 3);
            if (p.length < 3 || !isApp(p[1])) return "err not an app";
            return searchInApp(p[1], p[2].trim());
        }
        if (message.startsWith("find ") || message.startsWith("where ") || message.startsWith("play ")
                || message.equals("saved") || message.startsWith("save ") || message.startsWith("unsave ")) {
            return titleCommand(message);
        }
        if ("apps".equals(message)) return apps();
        if (message.startsWith("icon ")) return icon(message.substring(5).trim());
        if (message.startsWith("open ")) return open(message.substring(5).trim());
        if ("settings".equals(message)) {
            startActivity(new Intent(this, GlassSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return "ok";
        }
        if ("source".equals(message)) return helperCall("r") ? "ok" : "err keys aren't ready yet";
        if (message.startsWith("hold ")) {
            try {
                int key = Integer.parseInt(message.substring(5).trim());
                if (key != KEY_OK) return "err key not allowed";
                return helperCall("h " + key) ? "ok" : "err keys aren't ready yet";
            } catch (NumberFormatException e) {
                return "err bad key";
            }
        }
        if (message.startsWith("ask ")) return askAi(null, message.substring(4));
        if (message.startsWith("askvia ")) {
            String[] p = message.split(" ", 3);
            return p.length == 3 ? askAi(p[1], p[2]) : "err bad request";
        }
        if (message.startsWith("jarvis ")) return jarvis.start(message.substring(7), false);
        if (message.startsWith("jarvisshows ")) return jarvis.start(message.substring(12), true);
        if ("jarvisget".equals(message)) return jarvis.poll();
        if (message.startsWith("jarvisdry ")) return jarvis.dryRun(message.substring(10));
        if ("duck on".equals(message) || "duck off".equals(message)) {
            duck("duck on".equals(message));
            return "ok";
        }
        if (message.startsWith("say ")) {
            Voice.say(this, message.substring(4));
            return "ok";
        }
        if ("volget".equals(message)) return "vol " + volumePercent();
        if ("hush".equals(message)) {
            Voice.quiet();
            return "ok";
        }
        if ("watched".equals(message)) return "watched " + titles.watched();
        if ("watchedclear".equals(message)) {
            titles.forgetWatched();
            return "watched []";
        }
        if ("keys".equals(message)) return "keys " + Keys.status();
        if (message.startsWith("setkey ")) {
            String[] p = message.split(" ", 3);
            if (p.length < 2 || !Keys.NAMES.contains(p[1])) return "err no such key";
            return Keys.set(p[1], p.length == 3 ? p[2] : "") ? "keyset " + p[1] : "err couldn't save the key";
        }
        if ("phoneversion".equals(message)) return "phoneversion " + phoneVersion();
        if (message.startsWith("phoneapk ")) return phoneApkPart(message.substring(9).trim());
        if (message.startsWith("key ")) {
            int key;
            try {
                key = Integer.parseInt(message.substring(4).trim());
            } catch (NumberFormatException e) {
                return "err bad key";
            }
            if (!KEYS.contains(key)) return "err key not allowed";
            if (key == KEY_VOLUME_UP || key == KEY_VOLUME_DOWN || key == KEY_MUTE) {
                // Keys sent this way skip the glass volume bar's filter, so that service is asked directly.
                sendBroadcast(new Intent(RemoteKeyService.ACTION_VOLUME).setPackage(getPackageName()).putExtra(RemoteKeyService.EXTRA_KEY, key), "io.github.smartagentictv.tv.permission.INTERNAL");
                return "ok";
            }
            if (key == KEY_DELETE && usesOwnGrid() && KeyboardWatch.editField(null)) return "ok";
            return press(key) ? "ok" : "err keys aren't ready yet";
        }
        return "err unknown";
    }

    private String setVolume(int percent) {
        if (percent < 0 || percent > 100) return "err volume goes from 0 to 100";
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(percent * max / 100f), AudioManager.FLAG_SHOW_UI);
        return "ok";
    }

    /** Turns the TV down while someone talks to Jarvis, then back. If they changed the volume meanwhile, theirs stands. */
    private void duck(boolean on) {
        synchronized (duckLock) {
            AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            main.removeCallbacks(duckEnd);
            if (on) {
                if (duckedFrom < 0) {
                    int now = audio.getStreamVolume(AudioManager.STREAM_MUSIC), low = Math.max(1, now / DUCK_DIVIDE);
                    if (now > low) {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, low, 0);
                        duckedFrom = now;
                        duckedTo = low;
                    }
                }
                duckOwner = Thread.currentThread();
                main.postDelayed(duckEnd, DUCK_MAX_MS); // never left turned down, whatever happens to the phone
            } else {
                if (duckedFrom >= 0 && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == duckedTo) {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, duckedFrom, 0);
                }
                duckedFrom = -1;
                duckOwner = null;
            }
        }
    }

    // MARK: - For Jarvis

    /** The same commands a phone can send, with the same limits. */
    String command(String message) { return safely(message); }

    int volumePercent() {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        return Math.round(100f * audio.getStreamVolume(AudioManager.STREAM_MUSIC) / Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)));
    }

    boolean isYouTube(String pkg) { return YOUTUBE.equals(pkg); }

    String appOnScreen() {
        String pkg = KeyboardWatch.topPackage();
        if (pkg.isEmpty()) return "unknown";
        return getPackageName().equals(pkg) ? "the TV's home screen" : appLabel(pkg);
    }

    String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    String appNames() {
        StringBuilder s = new StringBuilder();
        for (String line : apps().substring(5).split("\n")) {
            String[] p = line.split("\t", 2);
            if (p.length == 2) s.append(s.length() == 0 ? "" : ", ").append(p[1]);
        }
        return s.toString();
    }

    /** The app whose name is closest to what was said, or null. */
    String appNamed(String said) {
        String wanted = Words.normal(said).replace(" ", "");
        if (wanted.isEmpty()) return null;
        String best = null;
        double bestScore = 0;
        for (String line : apps().substring(5).split("\n")) {
            String[] p = line.split("\t", 2);
            if (p.length != 2) continue;
            String name = Words.normal(p[1]).replace(" ", "");
            double score = name.contains(wanted) || wanted.contains(name) ? 1 : Words.alike(name, wanted);
            if (score > bestScore) {
                bestScore = score;
                best = p[0];
            }
        }
        return bestScore >= 0.7 ? best : null;
    }

    /** Opens a title by its link if the app has one, or searches for it inside the app. */
    String playTitle(String kind, int id, String pkg, String words) {
        if (!isApp(pkg)) return "err not an app";
        titles.remember(kind, id, words, pkg);
        return titles.open(kind, id, pkg) ? "played title" : searchInApp(pkg, words);
    }

    /** Base64 JPEG of the screen, or null if the app on screen blocks pictures. */
    String screenPicture() {
        synchronized (helperLock) {
            try {
                openHelper();
                helper.setSoTimeout(PICTURE_WAIT_MS);
                helperOut.write("p\n".getBytes(StandardCharsets.US_ASCII));
                helperOut.flush();
                String r = helperIn.readLine();
                helper.setSoTimeout(HELPER_WAIT_MS);
                return r != null && r.startsWith("ok ") ? r.substring(3) : null;
            } catch (IOException e) {
                closeHelper();
                return null;
            }
        }
    }

    private static boolean isPlainEnglish(String text) {
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) > 126) return false;
        return true;
    }

    private String askAi(String only, String question) {
        try {
            Ai.Answer a = ai.ask(AI_RULES, question, only);
            return "answer " + a.service + "|" + a.text;
        } catch (IOException e) {
            return "err the AI services didn't answer";
        }
    }

    private int phoneVersion() {
        try (InputStream in = getAssets().open("phone/version.txt")) {
            byte[] b = new byte[16];
            int n = in.read(b);
            return n <= 0 ? 0 : Integer.parseInt(new String(b, 0, n, StandardCharsets.US_ASCII).trim());
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    /** One piece of the phone app: "apk total base64". Over the encrypted link, so it can't be swapped on the way. */
    private String phoneApkPart(String offsetText) {
        try (InputStream in = getAssets().open("phone/MyTV.bin")) {
            int offset = Integer.parseInt(offsetText), total = in.available();
            if (offset < 0 || offset > total) return "err bad request";
            for (long skipped = 0; skipped < offset; ) {
                long n = in.skip(offset - skipped);
                if (n <= 0) return "err couldn't read the update";
                skipped += n;
            }
            byte[] part = new byte[APK_PART];
            int got = 0, n;
            while (got < part.length && (n = in.read(part, got, part.length - got)) > 0) got += n;
            return "apk " + total + " " + Base64.encodeToString(part, 0, got, Base64.NO_WRAP);
        } catch (IOException | NumberFormatException e) {
            return "err couldn't read the update";
        }
    }

    /** An app's own letter grid is showing, rather than Android's keyboard. */
    private boolean usesOwnGrid() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now < gridSeenUntil) return true; // looking takes a moment: don't look again for every letter
        boolean yes = !helperCall("k") && !YOUTUBE.equals(KeyboardWatch.topPackage()) && KeyboardWatch.letterGridOpen();
        if (yes) gridSeenUntil = now + GRID_REMEMBER_MS;
        return yes;
    }

    /** Android's own keyboard, an app's letter grid, or YouTube's keyboard, which only shows in the picture. */
    private boolean keyboardOpen() {
        if (helperCall("k")) return true;
        if (YOUTUBE.equals(KeyboardWatch.topPackage())) return helperCall("y");
        return KeyboardWatch.letterGridOpen();
    }

    /**
     * For a title with no link: opens the app, walks to its search with the remote keys, and types the title. Types only
     * once a keyboard is really showing, so a screen that looks different just stops there. Answers at once: this takes
     * a while on a cold start.
     */
    String searchInApp(String pkg, String words) {
        if (YOUTUBE.equals(pkg) && !words.isEmpty()) { // it takes a search link, so nothing needs pressing
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=" + android.net.Uri.encode(words)))
                        .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return "played results";
            } catch (android.content.ActivityNotFoundException e) {
                AppLog.w("youtube took no search link");
            }
        }
        int[] keys = SEARCH_KEYS.get(pkg);
        if (keys == null || words.isEmpty()) {
            startActivity(launchIntent(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return "played app";
        }
        if (!searching.compareAndSet(false, true)) return "err still opening the last one";
        thread(() -> {
            try {
                // Each try starts the app fresh at its home: resumed anywhere else, these keys could start a video.
                // Twice, because a slow start can swallow the first round of keys.
                for (int round = 0; round < 2 && (round == 0 || !keyboardOpen()); round++) {
                    startActivity(launchIntent(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    pause(round == 0 ? APP_START_MS : RETRY_MS);
                    if (!pkg.equals(KeyboardWatch.topPackage())) continue;
                    for (int key : keys) {
                        press(key);
                        pause(KEY_GAP_MS);
                    }
                    pause(SEARCH_OPEN_MS);
                }
                if (!keyboardOpen()) {
                    AppLog.w("search in " + pkg + ": no keyboard came up");
                    return;
                }
                gridSeenUntil = 0;
                if (!((usesOwnGrid() || !isPlainEnglish(words)) && KeyboardWatch.replaceField(words))) {
                    helperCall("t " + Base64.encodeToString(words.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
                }
            } catch (RuntimeException e) {
                AppLog.w("search in " + pkg + " failed: " + e);
            } finally {
                searching.set(false);
            }
        });
        return "played search";
    }

    private String titleCommand(String message) {
        try {
            String[] p = message.split(" ");
            if (message.startsWith("find ")) return "titles " + titles.smartFind(message.substring(5).trim(), jarvis::guessTitles);
            if (message.startsWith("where ")) return "where " + titles.where(Titles.checkedKind(p[1]), Integer.parseInt(p[2]));
            if (message.startsWith("play ")) {
                if (!isApp(p[3])) return "err not an app";
                String words = p.length > 4 ? message.substring(message.indexOf(p[3]) + p[3].length() + 1) : "";
                return playTitle(Titles.checkedKind(p[1]), Integer.parseInt(p[2]), p[3], words);
            }
            if (message.equals("saved")) return "saved " + titles.saved();
            if (message.startsWith("save ")) {
                titles.save(message.substring(5));
                return "saved " + titles.saved();
            }
            titles.unsave(p[1], Integer.parseInt(p[2]));
            return "saved " + titles.saved();
        } catch (IOException e) {
            return "err couldn't reach the service. Try again";
        } catch (org.json.JSONException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return "err bad request";
        }
    }

    private static int clamp(String number, int limit) {
        return Math.max(-limit, Math.min(limit, Integer.parseInt(number.trim())));
    }

    // MARK: - Apps

    /** Apps the TV can open, one "package TAB name" per line. */
    private String apps() {
        PackageManager pm = getPackageManager();
        java.util.TreeMap<String, String> found = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String pkg = r.activityInfo.packageName;
                if (getPackageName().equals(pkg) || Board.get(this).neverOpen().contains(pkg)) continue;
                if (!r.activityInfo.exported || r.activityInfo.permission != null) continue; // it would refuse to open
                String label = r.loadLabel(pm).toString().replaceAll("[\\t\\n]", " ");
                found.put(label + "\0" + pkg, pkg + "\t" + label);
            }
        }
        java.util.LinkedHashMap<String, String> byPackage = new java.util.LinkedHashMap<>();
        for (String line : found.values()) byPackage.putIfAbsent(line.split("\t", 2)[0], line);
        java.util.LinkedHashMap<String, String> ordered = new java.util.LinkedHashMap<>();
        for (String pkg : APPS_FIRST) {
            if (byPackage.containsKey(pkg)) ordered.put(pkg, byPackage.get(pkg));
        }
        for (java.util.Map.Entry<String, String> e : byPackage.entrySet()) ordered.putIfAbsent(e.getKey(), e.getValue());
        return "apps " + TextUtils.join("\n", ordered.values());
    }

    private boolean isApp(String pkg) {
        return !getPackageName().equals(pkg) && !Board.get(this).neverOpen().contains(pkg) && launchIntent(pkg) != null;
    }

    private Intent launchIntent(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    private String open(String pkg) {
        if (!isApp(pkg)) return "err not an app";
        startActivity(launchIntent(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return "ok";
    }

    /** The app's icon as a small PNG in base64. */
    private String icon(String pkg) {
        if (!isApp(pkg)) return "err not an app";
        try {
            Drawable d = getPackageManager().getApplicationIcon(pkg);
            Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 96, 96);
            d.draw(c);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            b.recycle();
            return "icon " + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (PackageManager.NameNotFoundException e) {
            return "err not an app";
        }
    }

    // MARK: - Pairing

    /** Shows a new code for two minutes. */
    synchronized String startPairing() {
        code = String.format(java.util.Locale.US, "%06d", RANDOM.nextInt(1_000_000));
        codeUntil = android.os.SystemClock.elapsedRealtime() + CODE_MS;
        tries = 0;
        return code;
    }

    synchronized void stopPairing() { code = null; }

    synchronized String currentCode() { return pairingCode(); }

    @Override public synchronized String pairingCode() {
        if (code != null && android.os.SystemClock.elapsedRealtime() > codeUntil) code = null;
        return code;
    }

    @Override public synchronized void pairFailed() {
        if (++tries >= MAX_TRIES) code = null; // too many wrong tries: this code is finished
    }

    @Override public String pairSucceeded(String phoneName, byte[] secret) {
        String id = Long.toHexString(RANDOM.nextLong());
        synchronized (phonesLock) {
            Properties phones = phones();
            phones.setProperty(id, hex(secret) + " " + phoneName.replaceAll("[\\r\\n]", " "));
            savePhones(phones);
        }
        stopPairing();
        AppLog.w("phone paired");
        return id;
    }

    @Override public byte[] secretFor(String id) {
        synchronized (phonesLock) {
            String entry = phones().getProperty(id);
            return entry == null ? null : unhex(entry.split(" ", 2)[0]);
        }
    }

    int phoneCount() {
        synchronized (phonesLock) { return phones().size(); }
    }

    void forgetPhones() {
        synchronized (phonesLock) { savePhones(new Properties()); }
    }

    private Properties phones() {
        Properties p = new Properties();
        File f = new File(getFilesDir(), PHONES_FILE);
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (IOException e) {
                AppLog.w("paired phones unreadable");
            }
        }
        return p;
    }

    private void savePhones(Properties p) {
        File tmp = new File(getFilesDir(), PHONES_FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            p.store(out, null);
            out.getFD().sync();
        } catch (IOException e) {
            AppLog.w("couldn't save paired phones");
            return;
        }
        if (!tmp.renameTo(new File(getFilesDir(), PHONES_FILE))) AppLog.w("couldn't save paired phones");
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(java.util.Locale.US, "%02x", x));
        return s.toString();
    }

    private static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    // MARK: - ADB's port and the key helper

    boolean locked() { return new File(getFilesDir(), LOCK_FILE).exists(); }

    String netState() { return netState; }

    void setLocked(boolean on) {
        File f = new File(getFilesDir(), LOCK_FILE);
        try {
            if (on) new FileOutputStream(f).close();
            else f.delete();
        } catch (IOException e) {
            AppLog.w("couldn't save the lock");
        }
        netState = "changing";
        setup.execute(this::applyAndStart);
    }

    /** At start: if the helper is still running from before (only this service restarted), the firewall is too. */
    private void prepare() {
        if (helperCurrent()) {
            netState = locked() ? "closed" : "open";
            return;
        }
        applyAndStart();
    }

    /** Firewall as chosen, then the helper. Restarting ADB as root and back ends any old helper, so it starts last. */
    private void applyAndStart() {
        boolean lock = locked();
        Board board = Board.get(this);
        String result;
        netState = "changing";
        try {
            try (Adb adb = connectAdb()) { adb.service("root:"); }
            pause(1500);
            try (Adb adb = connectAdb()) {
                String uid = String.valueOf(Process.myUid());
                String rule = "ipt() { while $1 -D INPUT -p tcp --dport " + ADB_PORT + " ! -i lo -j DROP 2>/dev/null; do :; done;"
                        + " while $1 -D OUTPUT -o lo -p tcp --dport " + ADB_PORT + " -m owner ! --uid-owner " + uid + " -j REJECT 2>/dev/null; do :; done;";
                if (lock) rule += " $1 -I INPUT -p tcp --dport " + ADB_PORT + " ! -i lo -j DROP;"
                        + " $1 -I OUTPUT -o lo -p tcp --dport " + ADB_PORT + " -m owner ! --uid-owner " + uid + " -j REJECT;";
                rule += " echo \"$1 $($1 -S | grep -c 'dport " + ADB_PORT + "')\"; }; ipt iptables; ipt ip6tables";
                String out = adb.shell(rule);
                result = out.contains("\niptables " + (lock ? 2 : 0)) || out.startsWith("iptables " + (lock ? 2 : 0))
                        ? (out.contains("ip6tables " + (lock ? 2 : 0)) ? (lock ? "closed" : "open") : "problem") : "problem";
                if ("problem".equals(result)) AppLog.w("firewall: " + out.trim());
                adb.service("unroot:");
            }
            pause(1500);
            synchronized (helperLock) { closeHelper(); }
            try (Adb adb = connectAdb()) {
                adb.shell("l=$(settings get secure enabled_accessibility_services); case \"$l\" in *" + WATCH + "*) ;; "
                        + "*) settings put secure enabled_accessibility_services \"$l:" + WATCH + "\";; esac; settings put secure accessibility_enabled 1");
                adb.shell("CLASSPATH=" + getPackageCodePath() + " setsid nohup app_process /system/bin io.github.smartagentictv.tv.KeyHelper "
                        + Process.myUid() + " " + helperArgument(board.mouse()) + " " + helperArgument(board.keyboard()) + " " + board.sourceScanCode() + " >/dev/null 2>&1 </dev/null & sleep 2"); // hanging up sooner kills it before it detaches
            }
            netState = result; // only now: ADB has finished restarting and the helper is up
            AppLog.w("phone link ready, ADB network port " + netState);
        } catch (IOException e) {
            netState = "problem";
            AppLog.w("phone link setup: " + e.getMessage());
        }
    }

    /** Device names have spaces, and go through the shell: only plain ones are passed on. */
    private static String helperArgument(String name) {
        return name.matches("[A-Za-z0-9 ._-]+") ? "'" + name + "'" : "''";
    }

    private Adb connectAdb() throws IOException {
        IOException last = null;
        for (int i = 0; i < 20; i++) {
            try {
                return Adb.connect(this, 10_000, null);
            } catch (IOException e) {
                last = e;
                pause(1000);
            }
        }
        throw last;
    }

    private boolean helperAlive() {
        synchronized (helperLock) {
            try {
                openHelper();
                return true;
            } catch (IOException e) {
                closeHelper();
                return false;
            }
        }
    }

    private boolean press(int key) { return helperCall(String.valueOf(key)); }

    /** One line to the helper. If it's gone (ADB restarted), starts it again for the next try. */
    private boolean helperCall(String line) {
        synchronized (helperLock) {
            try {
                openHelper();
                helperOut.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
                helperOut.flush();
                return "ok".equals(helperIn.readLine());
            } catch (IOException e) {
                closeHelper();
            }
        }
        if (preparing.compareAndSet(false, true)) {
            setup.execute(() -> {
                try { prepare(); } finally { preparing.set(false); }
            });
        }
        return false;
    }

    /** True when the running helper is this version. An older one, left from before an update, doesn't know "v". */
    private boolean helperCurrent() {
        synchronized (helperLock) {
            try {
                openHelper();
                helperOut.write("v\n".getBytes(StandardCharsets.US_ASCII));
                helperOut.flush();
                return KeyHelper.VERSION.equals(helperIn.readLine());
            } catch (IOException e) {
                closeHelper();
                return false;
            }
        }
    }

    private void openHelper() throws IOException {
        if (helper != null) return;
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress(KeyHelper.SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            s.setSoTimeout(HELPER_WAIT_MS);
            helperIn = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            helperOut = s.getOutputStream();
            helper = s;
        } catch (IOException e) {
            s.close();
            throw e;
        }
    }

    private void closeHelper() {
        if (helper != null) {
            try { helper.close(); } catch (IOException ignored) {}
        }
        helper = null;
        helperIn = null;
        helperOut = null;
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
