package io.github.smartagentictv.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.github.smartagentictv.wire.Wire;

/**
 * A TV remote: the round pad, volume, power and input, with tabs for a mouse touchpad, typing and shows.
 * One worker thread owns the connection and sends one command at a time, so a slow answer can't jumble the order.
 */
public class MainActivity extends Activity {
    private static final int BG = 0xFF101214, SURFACE = 0xFF1C1F23, RAISED = 0xFF2A2E33, PRESSED = 0xFF3D4248, INK = 0xFFE6E8EB, MUTED = 0xFF9AA0A6,
            ACCENT = 0xFF9DC3FF, ON_ACCENT = 0xFF0E1A2B, POWER = 26, PLAY_PAUSE = 85, REWIND = 89, FORWARD = 90;
    private static final int BACK = 4, HOME = 3, UP = 19, DOWN = 20, LEFT = 21, RIGHT = 22, OK = 23,
            VOLUME_UP = 24, VOLUME_DOWN = 25, MUTE = 164;
    private static final long REPEAT_FIRST_MS = 400, REPEAT_MS = 130;
    private static final int MAX_CHIPS = 3, MAX_CONTINUE = 6, APP_LINK_PORT = 47802;
    private static final String MOUSE_MOVED = "~moved", UPDATE = "~update";
    private static final int MODE_KEYS = 0, MODE_MOUSE = 1, MODE_TYPE = 2, MODE_SHOWS = 3, MODE_JARVIS = 4, ENTER = 66, TAB = 61, DELETE = 67, TYPE_CHUNK = 200;
    private static final String[][] LOOK_APPS = {{"in.startv.hotstar", "JioHotstar"}, {"com.graymatrix.did", "Zee5"},
            {"com.suntv.sunnxt", "Sun NXT"}, {"com.google.android.youtube.tv", "YouTube"}, {"playboxtv.tv.android.in", "PlayBox"}};
    private static final int HEAR_COMMAND = 0, HEAR_TYPING = 1, HEAR_SHOW = 2, HEAR_JARVIS = 3, ASK_MIC = 2, SPEECH_SCREEN = 7;
    private static final int VOICE_PHONE = 0, VOICE_TV = 1, VOICE_OFF = 2;
    private static final String[] VOICE_NAMES = {"the phone", "the TV", "nowhere (silent)"};
    private static final long JARVIS_POLL_MS = 300, JARVIS_GIVE_UP_MS = 70_000, IDLE_POLL_MS = 1000, REPLY_SHOW_MS = 15_000;
    /** What the language button shows, and what's asked of the speech service. */
    private static final String[][] LANGUAGES = {{"EN", "en-IN"}, {"हिं", "hi-IN"}, {"ಕ", "kn-IN"}};
    private static final String[] SPEED_NAMES = {"Slow", "Normal", "Fast"};
    private static final float[] SPEEDS = {0.8f, 1.5f, 2.6f};

    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Object moved = new Object();
    private int moveX, moveY;
    private boolean movePending;

    private SharedPreferences saved;
    private TextView status, speedButton, hideButton, showsHeading, updateBar;
    private final ImageView[] tabIcons = new ImageView[5];
    private final TextView[] tabLabels = new TextView[5];
    private static final String[] TAB_ICONS = {"remote", "mouse", "keyboard", "shows", "jarvis"}, TAB_NAMES = {"Remote", "Mouse", "Type", "Shows", "Jarvis"};
    private View showsPanel;
    private EditText showsField;
    private LinearLayout showsList;
    private JSONArray savedTitles = new JSONArray();
    private JSONObject openTitle;
    private final java.util.Map<String, Bitmap> posters = new java.util.HashMap<>();
    private EditText typeField;
    private View typePanel, remoteContent, mouseContent;
    private int mode = MODE_KEYS, modeBefore = MODE_KEYS;
    private String typedSoFar = "";
    private boolean silentChange, tvKeyboardOpen, openedByTv, hideTyping;
    private int closedPolls;
    private static final int CLOSED_POLLS = 3;
    private View dot;
    private EditText codeField;
    private View pairPage, remotePage;
    private LinearLayout appRow;
    private volatile Thread worker;
    private volatile Socket liveSocket;
    private final int accent = ACCENT;
    private volatile String openingApp = "the app";
    private SpeechRecognizer recognizer;
    private View listeningButton;
    private TextView languageButton;
    private int hearFor;
    private final java.util.Map<String, String> tvApps = new java.util.concurrent.ConcurrentHashMap<>();
    private JSONArray watchedTitles = new JSONArray();
    private View jarvisPanel, jarvisMic;
    private LinearLayout jarvisLog;
    private ScrollView jarvisScroll;
    private EditText jarvisField;
    private View yesNoRow;
    private volatile long jarvisAskedAt;
    private boolean waitingForAnswer;
    private TextToSpeech tts;
    private boolean ttsReady, speaking, listenAfterSpeech;
    private BargeIn bargeIn;
    private TextView replyBar;
    private boolean askedFromShows, fallBackToSearch;
    private String lastAsked = "";
    private final Runnable hideReply = () -> replyBar.setVisibility(View.GONE);

    // MARK: - Screen

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        saved = getSharedPreferences("tv", Context.MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        pairPage = buildPairPage();
        remotePage = buildRemotePage();
        root.addView(pairPage, new FrameLayout.LayoutParams(-1, -1));
        root.addView(remotePage, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        showPaired(isPaired());
        setMode(saved.getBoolean("mouseMode", false) ? MODE_MOUSE : MODE_KEYS);
    }

    private View buildPairPage() {
        LinearLayout page = column();
        page.setGravity(Gravity.CENTER);
        page.setPadding(dp(24), dp(48), dp(24), dp(24));
        LinearLayout panel = column();
        panel.setBackground(solid(24, SURFACE));
        panel.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView title = label("Pair with your TV", 26, Color.WHITE);
        panel.addView(title);
        TextView help = label("On the TV, open Settings, then Phone, then Show a pairing code.", 15, 0xCCFFFFFF);
        help.setPadding(0, dp(8), 0, dp(16));
        panel.addView(help);
        codeField = new EditText(this);
        codeField.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeField.setTextColor(Color.WHITE);
        codeField.setTextSize(26);
        codeField.setGravity(Gravity.CENTER);
        codeField.setHint("6-digit code");
        codeField.setHintTextColor(0x80FFFFFF);
        codeField.setBackground(solid(14, RAISED));
        codeField.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.addView(codeField, new LinearLayout.LayoutParams(-1, -2));
        TextView pair = button("Pair", 18);
        pair.setBackground(solid(16, ACCENT));
        pair.setTextColor(ON_ACCENT);
        pair.setOnClickListener(v -> startPairing());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
        lp.topMargin = dp(16);
        panel.addView(pair, lp);
        page.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        return page;
    }

    private View buildRemotePage() {
        LinearLayout page = column();
        page.setPadding(dp(16), dp(10), dp(16), 0);

        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        dot = new View(this);
        LinearLayout.LayoutParams dotSize = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotSize.rightMargin = dp(8);
        top.addView(dot, dotSize);
        status = label("Starting…", 14, MUTED);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        languageButton = label("", 13, INK);
        languageButton.setGravity(Gravity.CENTER);
        languageButton.setClickable(true);
        languageButton.setBackground(pressable(20, SURFACE));
        languageButton.setOnClickListener(v -> {
            tick(v);
            saved.edit().putInt("speech", (speechLanguage() + 1) % LANGUAGES.length).apply();
            showLanguage();
        });
        LinearLayout.LayoutParams languageLp = new LinearLayout.LayoutParams(dp(44), dp(40));
        languageLp.rightMargin = dp(6);
        top.addView(languageButton, languageLp);
        showLanguage();
        View more = iconButton("more");
        more.setBackground(null);
        more.setOnClickListener(v -> showMenu());
        top.addView(more, new LinearLayout.LayoutParams(dp(40), dp(44)));
        View power = iconButton("power");
        // Tested on this board: it goes fully off the network, so the phone can't turn it back on.
        power.setOnClickListener(v -> {
            tick(v);
            new AlertDialog.Builder(this).setTitle("Turn the TV off?")
                    .setMessage("You'll need the TV's own remote to turn it back on.")
                    .setPositiveButton("Turn off", (d, which) -> queue.offer("key " + POWER))
                    .setNegativeButton("Cancel", null).show();
        });
        LinearLayout.LayoutParams powerLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        powerLp.leftMargin = dp(4);
        top.addView(power, powerLp);
        page.addView(top, new LinearLayout.LayoutParams(-1, dp(52)));

        updateBar = label("A new version is ready. Tap to update.", 14, ON_ACCENT);
        updateBar.setGravity(Gravity.CENTER);
        updateBar.setBackground(solid(14, ACCENT));
        updateBar.setVisibility(View.GONE);
        updateBar.setOnClickListener(v -> {
            tick(v);
            updateBar.setVisibility(View.GONE);
            queue.offer(UPDATE);
        });
        LinearLayout.LayoutParams updateLp = new LinearLayout.LayoutParams(-1, dp(44));
        updateLp.topMargin = dp(4);
        page.addView(updateBar, updateLp);

        // What Jarvis answered, for when the question came from a mic outside the Jarvis tab.
        replyBar = label("", 15, INK);
        replyBar.setPadding(dp(14), dp(10), dp(14), dp(10));
        replyBar.setMaxLines(5);
        replyBar.setEllipsize(android.text.TextUtils.TruncateAt.END);
        replyBar.setBackground(pressable(14, SURFACE));
        replyBar.setVisibility(View.GONE);
        replyBar.setOnClickListener(v -> { tick(v); openedByTv = false; setMode(MODE_JARVIS); });
        LinearLayout.LayoutParams replyLp = new LinearLayout.LayoutParams(-1, -2);
        replyLp.topMargin = dp(4);
        page.addView(replyBar, replyLp);

        FrameLayout content = new FrameLayout(this);
        remoteContent = buildKeysContent();
        mouseContent = buildMouseContent();
        typePanel = buildTypePanel();
        showsPanel = buildShowsPanel();
        jarvisPanel = buildJarvisPanel();
        for (View v : new View[] {remoteContent, mouseContent, typePanel, showsPanel, jarvisPanel}) content.addView(v, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(-1, 0, 1);
        contentLp.topMargin = dp(8);
        page.addView(content, contentLp);

        LinearLayout tabs = row();
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int which = i;
            LinearLayout tab = column();
            tab.setGravity(Gravity.CENTER);
            tab.setClickable(true);
            tabIcons[i] = new ImageView(this);
            tabIcons[i].setScaleType(ImageView.ScaleType.CENTER);
            tab.addView(tabIcons[i], new LinearLayout.LayoutParams(dp(56), dp(30)));
            tabLabels[i] = label(TAB_NAMES[i], 12, MUTED);
            tabLabels[i].setGravity(Gravity.CENTER);
            tabLabels[i].setPadding(0, dp(2), 0, 0);
            tab.addView(tabLabels[i]);
            tab.setOnClickListener(v -> { tick(v); openedByTv = false; setMode(which); });
            tabs.addView(tab, new LinearLayout.LayoutParams(0, -1, 1));
        }
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(-1, dp(66));
        tabsLp.topMargin = dp(6);
        page.addView(tabs, tabsLp);
        return page;
    }

    private View buildKeysContent() {
        LinearLayout c = column();

        LinearLayout actions = row();
        actions.setGravity(Gravity.CENTER_VERTICAL);
        View input = iconButton("input");
        input.setOnClickListener(v -> { tick(v); queue.offer("source"); });
        actions.addView(input, new LinearLayout.LayoutParams(dp(52), dp(52)));
        View speak = micPill("Say a command", HEAR_COMMAND);
        LinearLayout.LayoutParams speakLp = new LinearLayout.LayoutParams(0, dp(52), 1);
        speakLp.leftMargin = dp(10);
        speakLp.rightMargin = dp(10);
        actions.addView(speak, speakLp);
        View settings = iconButton("settings");
        settings.setOnClickListener(v -> { tick(v); queue.offer("settings"); });
        actions.addView(settings, new LinearLayout.LayoutParams(dp(52), dp(52)));
        c.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(-1, 0, 1);
        padLp.topMargin = dp(14);
        padLp.bottomMargin = dp(14);
        c.addView(new DpadView(), padLp);

        LinearLayout nav = row();
        nav.addView(slot(iconKey("back", BACK), 60), slotParams());
        nav.addView(slot(iconKey("home", HOME), 60), slotParams());
        nav.addView(slot(iconKey("mute", MUTE), 60), slotParams());
        c.addView(nav, new LinearLayout.LayoutParams(-1, dp(60)));

        LinearLayout media = row();
        media.addView(slot(iconKey("rewind", REWIND), 48), slotParams());
        media.addView(slot(iconKey("playpause", PLAY_PAUSE), 48), slotParams());
        media.addView(slot(iconKey("forward", FORWARD), 48), slotParams());
        LinearLayout.LayoutParams mediaLp = new LinearLayout.LayoutParams(-1, dp(48));
        mediaLp.topMargin = dp(8);
        c.addView(media, mediaLp);

        LinearLayout volume = row();
        volume.setGravity(Gravity.CENTER_VERTICAL);
        volume.setBackground(solid(26, SURFACE));
        View quieter = held(iconButton("minus"), "key " + VOLUME_DOWN);
        quieter.setBackground(pressable(26, SURFACE));
        volume.addView(quieter, new LinearLayout.LayoutParams(0, -1, 1));
        TextView vol = label("VOL", 12, MUTED);
        vol.setGravity(Gravity.CENTER);
        volume.addView(vol, new LinearLayout.LayoutParams(dp(44), -2));
        View louder = held(iconButton("plus"), "key " + VOLUME_UP);
        louder.setBackground(pressable(26, SURFACE));
        volume.addView(louder, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams volumeLp = new LinearLayout.LayoutParams(-1, dp(52));
        volumeLp.topMargin = dp(10);
        c.addView(volume, volumeLp);

        HorizontalScrollView apps = new HorizontalScrollView(this);
        apps.setHorizontalScrollBarEnabled(false);
        appRow = new LinearLayout(this);
        apps.addView(appRow);
        LinearLayout.LayoutParams appsLp = new LinearLayout.LayoutParams(-1, dp(72));
        appsLp.topMargin = dp(10);
        c.addView(apps, appsLp);
        return c;
    }

    private View buildMouseContent() {
        LinearLayout c = column();
        c.addView(new TouchpadView(), new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout main = row();
        main.setGravity(Gravity.CENTER_VERTICAL);
        main.addView(iconKey("back", BACK), new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView click = button("Click", 16);
        click.setOnClickListener(v -> { tick(v); queue.offer("click"); });
        LinearLayout.LayoutParams clickLp = new LinearLayout.LayoutParams(0, dp(56), 1);
        clickLp.leftMargin = dp(10);
        clickLp.rightMargin = dp(10);
        main.addView(click, clickLp);
        main.addView(iconKey("home", HOME), new LinearLayout.LayoutParams(dp(56), dp(56)));
        c.addView(main, rowParams());

        LinearLayout extra = row();
        extra.setGravity(Gravity.CENTER_VERTICAL);
        extra.addView(slot(held(iconButton("up"), "scroll 2"), 48), slotParams());
        TextView scrollWord = label("Scroll", 12, MUTED);
        scrollWord.setGravity(Gravity.CENTER);
        extra.addView(scrollWord, new LinearLayout.LayoutParams(dp(44), -2));
        extra.addView(slot(held(iconButton("down"), "scroll -2"), 48), slotParams());
        speedButton = button("", 14);
        speedButton.setOnClickListener(v -> {
            tick(v);
            saved.edit().putInt("speed", (speed() + 1) % SPEEDS.length).apply();
            showSpeed();
        });
        LinearLayout.LayoutParams speedLp = new LinearLayout.LayoutParams(0, dp(48), 1);
        extra.addView(speedButton, speedLp);
        c.addView(extra, rowParams());
        showSpeed();
        return c;
    }

    // MARK: - Shows

    private View buildShowsPanel() {
        LinearLayout panel = column();
        showsField = new EditText(this);
        showsField.setSingleLine(true);
        showsField.setInputType(InputType.TYPE_CLASS_TEXT);
        showsField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        showsField.setTextColor(Color.WHITE);
        showsField.setTextSize(18);
        showsField.setHint("Search movies and shows");
        showsField.setHintTextColor(MUTED);
        showsField.setBackground(solid(24, SURFACE));
        showsField.setPadding(dp(18), dp(12), dp(12), dp(12));
        showsField.setOnEditorActionListener((v, action, e) -> {
            searchShows(v.getText().toString().trim());
            return true;
        });
        LinearLayout searchRow = row();
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.addView(showsField, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams showMicLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        showMicLp.leftMargin = dp(8);
        searchRow.addView(micButton(HEAR_SHOW), showMicLp);
        panel.addView(searchRow, new LinearLayout.LayoutParams(-1, -2));
        showsHeading = label("Saved", 14, MUTED);
        showsHeading.setPadding(dp(4), dp(12), dp(4), dp(8));
        panel.addView(showsHeading);
        ScrollView scroll = new ScrollView(this);
        showsList = column();
        scroll.addView(showsList);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return panel;
    }

    private void searchShows(String words) {
        if (words.isEmpty()) {
            queue.offer("saved");
        } else {
            showsHeading.setText("Searching…");
            showsList.removeAllViews();
            queue.offer("find " + words);
        }
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(showsField.getWindowToken(), 0);
    }

    // MARK: - Voice

    private int speechLanguage() { return Math.max(0, Math.min(LANGUAGES.length - 1, saved.getInt("speech", 0))); }

    private void showLanguage() { languageButton.setText(LANGUAGES[speechLanguage()][0]); }

    private View micButton(int purpose) {
        View b = iconButton("mic");
        b.setOnClickListener(v -> toggleMic(purpose, v));
        return b;
    }

    /** The mic with words beside it. */
    private View micPill(String words, int purpose) {
        LinearLayout pill = row();
        pill.setGravity(Gravity.CENTER);
        pill.setClickable(true);
        pill.setBackground(pressable(26, SURFACE));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(new Icons("mic", INK, dp(22)));
        pill.addView(icon);
        TextView text = label(words, 14, INK);
        text.setPadding(dp(8), 0, 0, 0);
        text.setSingleLine(true);
        pill.addView(text);
        pill.setOnClickListener(v -> toggleMic(purpose, v));
        return pill;
    }

    private void toggleMic(int purpose, View v) {
        tick(v);
        boolean wasSpeaking = speaking;
        stopSpeaking();
        if (wasSpeaking || listeningButton == null) listen(purpose, v);
        else {
            stopListening();
            queue.offer("duck off");
        }
    }

    private void listen(int purpose, View button) {
        if (purpose == HEAR_TYPING && hideTyping) {
            toastLine("The mic is off while Hide is on: spoken passwords can be overheard");
            return;
        }
        hearFor = purpose;
        listeningButton = button;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, ASK_MIC);
            return;
        }
        startHearing();
    }

    private void startHearing() {
        android.content.Intent ask = new android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGES[speechLanguage()][1])
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGES[speechLanguage()][1])
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // No background speech service on this phone: its own speech screen may still be there.
            listeningButton = null;
            try {
                startActivityForResult(ask, SPEECH_SCREEN);
            } catch (android.content.ActivityNotFoundException e) {
                toastLine("This phone has no speech service. Install or update the Google app");
            }
            return;
        }
        listeningButton.setBackground(solid(26, 0xFF3A5A8C));
        toastLine("Listening…");
        final boolean ducking = hearFor == HEAR_COMMAND || hearFor == HEAR_JARVIS;
        if (ducking) queue.offer("duck on");
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onResults(Bundle results) {
                java.util.ArrayList<String> heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                stopListening();
                if (heard != null && !heard.isEmpty()) heard(heard.get(0));
                else {
                    toastLine("Didn't catch that. Try again");
                    if (ducking) queue.offer("duck off");
                }
            }
            @Override public void onError(int error) {
                stopListening();
                toastLine(speechProblem(error));
                if (ducking) queue.offer("duck off");
            }
            @Override public void onReadyForSpeech(Bundle b) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float v) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() { toastLine("Working it out…"); }
            @Override public void onPartialResults(Bundle b) {}
            @Override public void onEvent(int type, Bundle b) {}
        });
        recognizer.startListening(ask);
    }

    private String speechProblem(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "Didn't catch that. Try again";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Speech needs the internet, or this language downloaded on your phone";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Allow the microphone for My TV in your phone's settings";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "The phone's speech service is busy. Try again";
            default:
                return LANGUAGES[speechLanguage()][0] + " speech isn't ready on this phone (" + error + ")";
        }
    }

    private void stopListening() {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        if (listeningButton != null) {
            listeningButton.setBackground(pressable(26, SURFACE));
            listeningButton = null;
        }
    }

    /** What was said goes where the mic was: into the typing box, the show search, or the command rules. */
    private void heard(String words) {
        if (hearFor == HEAR_TYPING) {
            String now = typeField.getText().toString();
            typeField.append(now.isEmpty() || now.endsWith(" ") ? words : " " + words);
            toastLine("Typed what you said");
        } else if (hearFor == HEAR_SHOW) {
            showsField.setText(words);
            showsHeading.setText("Searching…");
            showsList.removeAllViews();
            askJarvis(words, true);
        } else if (hearFor == HEAR_JARVIS) {
            askJarvis(words);
        } else {
            runVoiceCommand(words);
        }
    }

    /** Simple commands work by rules, at once and without the AI. Everything else, and any reply to a question, goes to Jarvis. */
    private void runVoiceCommand(String words) {
        VoiceCommands.Result r = VoiceCommands.parse(words, tvApps);
        boolean wantsPlaying = r.search != null && words.trim().toLowerCase(java.util.Locale.ROOT).startsWith("play ");
        if (waitingForAnswer || !r.understood() || wantsPlaying) {
            fallBackToSearch = wantsPlaying;
            lastAsked = r.search == null ? words : r.search;
            askJarvis(words);
            return;
        }
        queue.offer("duck off");
        toastLine("Heard \"" + words + "\"");
        if (r.search != null) {
            openedByTv = false;
            setMode(MODE_SHOWS);
            showsField.setText(r.search);
            searchShows(r.search);
            return;
        }
        for (String c : r.commands) queue.offer(c);
        if (r.openTyping) {
            openedByTv = false;
            setMode(MODE_TYPE);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] names, int[] results) {
        if (code != ASK_MIC) return;
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED && listeningButton != null) {
            startHearing();
        } else {
            listeningButton = null;
            toastLine("The mic needs permission. You can allow it in your phone's settings");
        }
    }

    @Override protected void onActivityResult(int code, int result, android.content.Intent data) {
        super.onActivityResult(code, result, data);
        if (code != SPEECH_SCREEN || result != RESULT_OK || data == null) return;
        java.util.ArrayList<String> said = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (said != null && !said.isEmpty()) heard(said.get(0));
    }

    // MARK: - Jarvis

    private View buildJarvisPanel() {
        LinearLayout panel = column();
        jarvisScroll = new ScrollView(this);
        jarvisLog = column();
        jarvisLog.setPadding(0, dp(4), 0, dp(4));
        jarvisScroll.addView(jarvisLog);
        panel.addView(jarvisScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        addBubble("Ask me anything, or tell me what to watch. Try \"play Kantara\", \"something funny for tonight\", \"what's on my screen\" or \"remind me in 10 minutes\".", false);

        LinearLayout askRow = row();
        askRow.setGravity(Gravity.CENTER_VERTICAL);
        jarvisField = new EditText(this);
        jarvisField.setSingleLine(true);
        jarvisField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        jarvisField.setImeOptions(EditorInfo.IME_ACTION_SEND);
        jarvisField.setTextColor(Color.WHITE);
        jarvisField.setTextSize(16);
        jarvisField.setHint("Ask Jarvis");
        jarvisField.setHintTextColor(MUTED);
        jarvisField.setBackground(solid(24, SURFACE));
        jarvisField.setPadding(dp(18), dp(12), dp(12), dp(12));
        jarvisField.setOnEditorActionListener((v, action, e) -> {
            sendTyped();
            return true;
        });
        askRow.addView(jarvisField, new LinearLayout.LayoutParams(0, -2, 1));
        View send = iconButton("send");
        send.setOnClickListener(v -> { tick(v); sendTyped(); });
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        sendLp.leftMargin = dp(8);
        askRow.addView(send, sendLp);
        jarvisMic = micButton(HEAR_JARVIS);
        LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        micLp.leftMargin = dp(8);
        askRow.addView(jarvisMic, micLp);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(8);
        panel.addView(askRow, rowLp);
        return panel;
    }

    private void sendTyped() {
        String words = jarvisField.getText().toString().trim();
        if (words.isEmpty()) return;
        jarvisField.setText("");
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(jarvisField.getWindowToken(), 0);
        stopSpeaking();
        askJarvis(words);
    }

    private void askJarvis(String words) { askJarvis(words, false); }

    private void askJarvis(String words, boolean fromShows) {
        askedFromShows = fromShows;
        if (fromShows) lastAsked = words;
        stopSpeaking();
        clearYesNo();
        waitingForAnswer = false;
        addBubble(words, true);
        toastLine("Thinking…");
        jarvisAskedAt = System.currentTimeMillis();
        queue.offer((fromShows ? "jarvisshows " : "jarvis ") + words);
    }

    private void addBubble(String text, boolean mine) {
        TextView b = label(text, 15, mine ? ON_ACCENT : INK);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setBackground(solid(16, mine ? ACCENT : SURFACE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = mine ? Gravity.END : Gravity.START;
        lp.topMargin = dp(6);
        lp.leftMargin = mine ? dp(40) : 0;
        lp.rightMargin = mine ? 0 : dp(40);
        jarvisLog.addView(b, lp);
        scrollDown();
    }

    private void scrollDown() {
        jarvisScroll.post(() -> jarvisScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void clearYesNo() {
        if (yesNoRow != null) jarvisLog.removeView(yesNoRow);
        yesNoRow = null;
    }

    /** Runs on the worker: an answer has come from the TV. */
    private void jarvisAnswer(String json) {
        runOnUiThread(() -> {
            toastLine("Connected to TV");
            JSONObject a;
            try {
                a = new JSONObject(json);
            } catch (JSONException e) {
                addBubble("I couldn't read the answer from the TV.", false);
                queue.offer("duck off");
                return;
            }
            String say = a.optString("say");
            addBubble(say, false);
            JSONArray titles = a.optJSONArray("titles");
            for (int i = 0; titles != null && i < titles.length(); i++) {
                JSONObject t = titles.optJSONObject(i);
                if (t != null) jarvisLog.addView(titleRow(t));
            }
            waitingForAnswer = a.optBoolean("ask");
            if (a.optBoolean("confirm")) addYesNo();
            boolean inShows = askedFromShows && mode == MODE_SHOWS, hasTitles = titles != null && titles.length() > 0;
            if (inShows && hasTitles) showTitles(a.optString("heading", "Results"), titles, "Nothing found");
            else if (inShows) showsHeading.setText("Results");
            // With the AI out of reach, a title can still be looked up by the plain search.
            if (a.optBoolean("failed") && (askedFromShows || fallBackToSearch)) {
                if (mode != MODE_SHOWS) setMode(MODE_SHOWS);
                showsField.setText(lastAsked);
                searchShows(lastAsked);
            }
            fallBackToSearch = false;
            askedFromShows = false;
            showReply(say);
            boolean look = a.optBoolean("confirm") || (hasTitles && !inShows);
            if (look && mode != MODE_JARVIS) {
                openedByTv = false;
                setMode(MODE_JARVIS);
            }
            scrollDown();
            speak(say, waitingForAnswer);
        });
    }

    private void showReply(String say) {
        ui.removeCallbacks(hideReply);
        if (mode == MODE_JARVIS || say.isEmpty()) {
            replyBar.setVisibility(View.GONE);
            return;
        }
        replyBar.setText(say);
        replyBar.setVisibility(View.VISIBLE);
        ui.postDelayed(hideReply, REPLY_SHOW_MS);
    }

    private void addYesNo() {
        LinearLayout r = row();
        TextView yes = button("Yes", 15), no = button("No", 15);
        yes.setBackground(solid(14, ACCENT));
        yes.setTextColor(ON_ACCENT);
        no.setBackground(pressable(14, RAISED));
        yes.setOnClickListener(v -> { tick(v); askJarvis("yes"); });
        no.setOnClickListener(v -> { tick(v); askJarvis("no"); });
        r.addView(yes, weighted());
        r.addView(no, weighted());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.topMargin = dp(6);
        jarvisLog.addView(r, lp);
        yesNoRow = r;
    }

    private int voicePlace() { return Math.max(0, Math.min(VOICE_NAMES.length - 1, saved.getInt("voicePlace", VOICE_PHONE))); }

    private void prepareSpeech() {
        if (tts != null) return;
        tts = new TextToSpeech(this, status -> runOnUiThread(() -> {
            if (status != TextToSpeech.SUCCESS || tts == null) {
                ttsReady = false;
                return;
            }
            int fit = tts.setLanguage(new java.util.Locale("en", "IN"));
            if (fit < 0) tts.setLanguage(java.util.Locale.ENGLISH);
            tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { runOnUiThread(MainActivity.this::speechFinished); }
                @Override public void onError(String id) { runOnUiThread(MainActivity.this::speechFinished); }
            });
            ttsReady = true;
        }));
    }

    /** Spoken where the menu says. The TV stays turned down through the phone's voice, but not for its own. */
    private void speak(String line, boolean thenListen) {
        listenAfterSpeech = thenListen;
        int place = voicePlace();
        if (place == VOICE_PHONE && ttsReady && !line.isEmpty()) {
            speaking = true;
            queue.offer("duck on");
            startBargeIn();
            tts.speak(line, TextToSpeech.QUEUE_FLUSH, null, "reply");
            return;
        }
        queue.offer("duck off");
        if (place == VOICE_TV && !line.isEmpty()) queue.offer("say " + line);
        if (thenListen && place != VOICE_TV) speechFinished();
    }

    private void startBargeIn() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        bargeIn = new BargeIn(() -> runOnUiThread(() -> {
            if (!speaking) return;
            stopSpeaking();
            listen(HEAR_JARVIS, jarvisMic);
        }));
        bargeIn.start();
    }

    private void stopBargeIn() {
        if (bargeIn != null) bargeIn.stop();
        bargeIn = null;
    }

    private void stopSpeaking() {
        stopBargeIn();
        listenAfterSpeech = false;
        if (speaking && tts != null) tts.stop();
        speaking = false;
        if (voicePlace() == VOICE_TV) queue.offer("hush");
    }

    private void speechFinished() {
        stopBargeIn();
        speaking = false;
        if (listenAfterSpeech && worker != null && listeningButton == null) {
            listenAfterSpeech = false;
            listen(HEAR_JARVIS, jarvisMic);
        } else {
            queue.offer("duck off");
        }
    }

    private void showTitles(String heading, JSONArray list, String empty) {
        runOnUiThread(() -> {
            showsHeading.setText(list.length() == 0 ? empty : heading);
            showsList.removeAllViews();
            for (int i = 0; i < list.length(); i++) {
                JSONObject t = list.optJSONObject(i);
                if (t != null) showsList.addView(titleRow(t));
            }
        });
    }

    private View titleRow(JSONObject t) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setPadding(dp(6), dp(6), dp(6), dp(6));
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[] {android.R.attr.state_pressed}, solid(12, RAISED));
        d.addState(new int[] {}, solid(12, 0x00000000));
        row.setBackground(d);
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(solid(6, SURFACE));
        poster.setClipToOutline(true);
        row.addView(poster, new LinearLayout.LayoutParams(dp(48), dp(72)));
        loadPoster(t.optString("poster"), poster);
        LinearLayout text = column();
        text.setPadding(dp(12), 0, dp(6), 0);
        text.addView(label(t.optString("title"), 16, INK));
        String kind = t.optString("kind").equals("tv") ? "Show" : "Movie", year = t.optString("year");
        text.addView(label(year.isEmpty() ? kind : kind + " · " + year, 13, MUTED));
        JSONArray apps = appsOf(t);
        if (apps != null) {
            LinearLayout chips = new LinearLayout(this);
            chips.setPadding(0, dp(6), 0, 0);
            for (int i = 0; i < apps.length() && i < MAX_CHIPS; i++) {
                final JSONObject app = apps.optJSONObject(i);
                TextView chip = label(app.optString("name"), 12, ON_ACCENT);
                chip.setSingleLine(true);
                chip.setPadding(dp(10), dp(4), dp(10), dp(5));
                chip.setBackground(solid(11, ACCENT));
                chip.setOnClickListener(v -> { tick(v); playTitle(t, app); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = dp(6);
                chips.addView(chip, lp);
            }
            if (apps.length() == 0) chips.addView(label("Tap to search in an app", 12, MUTED));
            text.addView(chips);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        // One app: straight there. Otherwise, or holding the row, the choices, the story and Save.
        row.setOnClickListener(v -> {
            tick(v);
            if (apps != null && apps.length() == 1) playTitle(t, apps.optJSONObject(0));
            else showDetails(t);
        });
        row.setOnLongClickListener(v -> {
            tick(v);
            showDetails(t);
            return true;
        });
        return row;
    }

    /** The apps this row knows of, or null if it was saved before results carried them. */
    private static JSONArray appsOf(JSONObject t) {
        if (t.has("pkg")) {
            try {
                return new JSONArray().put(new JSONObject().put("pkg", t.optString("pkg")).put("name", t.optString("name")));
            } catch (JSONException e) {
                return null;
            }
        }
        return t.optJSONArray("apps");
    }

    private void playTitle(JSONObject t, JSONObject app) {
        openingApp = app.optString("name");
        toastLine("Opening in " + openingApp + "…");
        queue.offer("play " + t.optString("kind") + " " + t.optInt("id") + " " + app.optString("pkg") + " " + t.optString("title"));
    }

    private void showDetails(JSONObject t) {
        openTitle = t;
        queue.offer("where " + t.optString("kind") + " " + t.optInt("id"));
        toastLine("Finding where to watch…");
    }

    /** Continue watching, then saved, when the search box is empty. */
    private void showShowsHome() {
        runOnUiThread(() -> {
            if (!showsField.getText().toString().trim().isEmpty()) return;
            showsList.removeAllViews();
            boolean any = watchedTitles.length() > 0;
            showsHeading.setText(any ? "Continue watching" : savedTitles.length() > 0 ? "Saved" : "Nothing saved yet. Search for a show to add it.");
            for (int i = 0; i < watchedTitles.length() && i < MAX_CONTINUE; i++) {
                JSONObject t = watchedTitles.optJSONObject(i);
                if (t != null) showsList.addView(titleRow(t));
            }
            if (any && savedTitles.length() > 0) {
                TextView heading = label("Saved", 14, MUTED);
                heading.setPadding(dp(4), dp(12), dp(4), dp(8));
                showsList.addView(heading);
            }
            for (int i = 0; i < savedTitles.length(); i++) {
                JSONObject t = savedTitles.optJSONObject(i);
                if (t != null) showsList.addView(titleRow(t));
            }
        });
    }

    /** Posters come straight from TMDB's public image server: no key, and it's small enough for a list. */
    private void loadPoster(String path, ImageView into) {
        if (path.isEmpty()) return;
        Bitmap known = posters.get(path);
        if (known != null) {
            into.setImageBitmap(known);
            return;
        }
        into.setTag(path);
        new Thread(() -> {
            try (java.io.InputStream in = new java.net.URL("https://image.tmdb.org/t/p/w154" + path).openStream()) {
                Bitmap b = BitmapFactory.decodeStream(in);
                if (b == null) return;
                runOnUiThread(() -> {
                    posters.put(path, b);
                    if (path.equals(into.getTag())) into.setImageBitmap(b);
                });
            } catch (IOException e) {
                // The row just has no picture.
            }
        }).start();
    }

    private boolean isSaved(JSONObject t) {
        for (int i = 0; i < savedTitles.length(); i++) {
            JSONObject s = savedTitles.optJSONObject(i);
            if (s != null && s.optInt("id") == t.optInt("id") && s.optString("kind").equals(t.optString("kind"))) return true;
        }
        return false;
    }

    /** One title: its story, a button for each app of this TV that has it, and save. */
    private void showWhere(String json) {
        final JSONObject t = openTitle;
        if (t == null) return;
        runOnUiThread(() -> {
            JSONObject w;
            try {
                w = new JSONObject(json);
            } catch (JSONException e) {
                return;
            }
            toastLine("Connected to TV");
            LinearLayout box = column();
            box.setPadding(dp(20), dp(8), dp(20), dp(8));
            ScrollView scroll = new ScrollView(this);
            scroll.addView(box);
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle(t.optString("title")).setView(scroll).create();
            JSONArray apps = w.optJSONArray("apps");
            for (int i = 0; apps != null && i < apps.length(); i++) {
                JSONObject app = apps.optJSONObject(i);
                TextView b = button("Open in " + app.optString("name"), 15);
                b.setBackground(solid(14, accent));
                b.setTextColor(0xFF0B0B10);
                b.setOnClickListener(v -> {
                    tick(v);
                    queue.offer("play " + t.optString("kind") + " " + t.optInt("id") + " " + app.optString("pkg") + " " + t.optString("title"));
                    openingApp = app.optString("name");
                    dialog.dismiss();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
                lp.bottomMargin = dp(8);
                box.addView(b, lp);
            }
            JSONArray elsewhere = w.optJSONArray("elsewhere");
            if (apps == null || apps.length() == 0) {
                box.addView(label(elsewhere != null && elsewhere.length() > 0
                        ? "None of the apps on your TV have this. It's on: " + join(elsewhere)
                        : "No streaming app is listed for this in India.", 14, Color.GRAY));
            } else if (elsewhere != null && elsewhere.length() > 0) {
                box.addView(label("Also on: " + join(elsewhere), 13, Color.GRAY));
            }
            // Streaming data is thin for regional shows, so every title can also be looked for in the main apps.
            TextView lookHeading = label(apps == null || apps.length() == 0 ? "Search for it in:" : "Or search in:", 14, Color.GRAY);
            lookHeading.setPadding(0, dp(10), 0, dp(6));
            box.addView(lookHeading);
            LinearLayout lookRow = null;
            for (int i = 0; i < LOOK_APPS.length; i++) {
                if (i % 2 == 0) {
                    lookRow = row();
                    box.addView(lookRow, new LinearLayout.LayoutParams(-1, dp(52)));
                }
                final String[] look = LOOK_APPS[i];
                TextView b = button(look[1], 14);
                b.setBackground(solid(14, accent));
                b.setTextColor(0xFF0B0B10);
                b.setOnClickListener(v -> {
                    tick(v);
                    openingApp = look[1];
                    queue.offer("play " + t.optString("kind") + " " + t.optInt("id") + " " + look[0] + " " + t.optString("title"));
                    dialog.dismiss();
                });
                lookRow.addView(b, weighted());
            }
            TextView story = label(w.optString("overview"), 14, Color.GRAY);
            story.setPadding(0, dp(10), 0, dp(10));
            box.addView(story);
            final boolean isSaved = isSaved(t);
            dialog.setButton(AlertDialog.BUTTON_POSITIVE, isSaved ? "Remove from saved" : "Save", (d, which) -> {
                if (isSaved) queue.offer("unsave " + t.optString("kind") + " " + t.optInt("id"));
                else queue.offer("save " + t.toString());
            });
            dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Close", (d, which) -> {});
            dialog.show();
        });
    }

    private static String join(JSONArray a) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < a.length(); i++) s.append(i == 0 ? "" : ", ").append(a.optString(i));
        return s.toString();
    }

    /** Answers that fill the Shows tab. True if this was one of them. */
    private boolean showsAnswer(String answer) {
        try {
            if (answer.startsWith("titles ")) {
                showTitles("Results", new JSONArray(answer.substring(7)), "Nothing found");
            } else if (answer.startsWith("saved ")) {
                savedTitles = new JSONArray(answer.substring(6));
                showShowsHome();
            } else if (answer.startsWith("keys ")) {
                showKeys(answer.substring(5));
            } else if (answer.startsWith("keyset ")) {
                toastLine("Saved on the TV");
            } else if (answer.startsWith("watched ")) {
                watchedTitles = new JSONArray(answer.substring(8));
                showShowsHome();
            } else if (answer.startsWith("where ")) {
                showWhere(answer.substring(6));
            } else if (answer.startsWith("played ")) {
                toastLine(answer.endsWith("title") ? "Opened in " + openingApp + ". Press OK if it doesn't start."
                        : answer.endsWith("results") ? "Showing " + openingApp + " results on the TV."
                        : answer.endsWith("search") ? "Searching " + openingApp + " for it. This can take 30 seconds."
                        : "Opened " + openingApp + ". Search for it there.");
            } else {
                return false;
            }
            return true;
        } catch (JSONException e) {
            toastLine("Couldn't read the answer from the TV");
            return true;
        }
    }

    /** What you type here appears on the TV as you type: only the change goes across, as new letters or deletes. */
    private View buildTypePanel() {
        LinearLayout panel = column();
        panel.addView(label("Pick a search box or sign-in field on the TV, then type here.", 14, MUTED));
        typeField = new EditText(this);
        typeField.setTextColor(Color.WHITE);
        typeField.setTextSize(20);
        typeField.setHint("Type here");
        typeField.setHintTextColor(MUTED);
        typeField.setBackground(solid(16, SURFACE));
        typeField.setPadding(dp(16), dp(14), dp(16), dp(14));
        typeField.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        typeField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        applyFieldType();
        typeField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (!silentChange) sendChange(s.toString());
            }
        });
        typeField.setOnEditorActionListener((v, action, e) -> {
            pressEnter();
            return true;
        });
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(-1, -2);
        fieldLp.topMargin = dp(12);
        panel.addView(typeField, fieldLp);
        LinearLayout buttons = row();
        TextView enter = button("Enter", 15);
        enter.setOnClickListener(v -> { tick(v); pressEnter(); });
        buttons.addView(enter, weighted());
        TextView tab = tapping("Next box", "key " + TAB);
        tab.setOnClickListener(v -> { tick(v); queue.offer("key " + TAB); clearFieldQuietly(); });
        buttons.addView(tab, weighted());
        hideButton = button("Hide", 15);
        hideButton.setOnClickListener(v -> {
            tick(v);
            hideTyping = !hideTyping;
            applyFieldType();
        });
        buttons.addView(hideButton, weighted());
        TextView clear = button("Clear", 15);
        clear.setOnClickListener(v -> { tick(v); typeField.setText(""); });
        buttons.addView(clear, weighted());
        panel.addView(buttons, rowParams());
        View speak = micPill("Speak", HEAR_TYPING);
        LinearLayout.LayoutParams speakLp = new LinearLayout.LayoutParams(-1, dp(52));
        speakLp.topMargin = dp(10);
        panel.addView(speak, speakLp);
        return panel;
    }

    /** No suggestions and no learning: this field carries passwords. */
    private void applyFieldType() {
        int cursor = typeField.getSelectionEnd();
        boolean keep = silentChange;
        silentChange = true;
        typeField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (hideTyping ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        typeField.setTypeface(Typeface.DEFAULT); // these input types would otherwise switch to a typewriter font
        if (cursor >= 0) typeField.setSelection(Math.min(cursor, typeField.length()));
        silentChange = keep;
        if (hideButton != null) hideButton.setText(hideTyping ? "Show" : "Hide");
    }

    private void sendChange(String now) {
        int same = 0, limit = Math.min(typedSoFar.length(), now.length());
        while (same < limit && typedSoFar.charAt(same) == now.charAt(same)) same++;
        for (int i = typedSoFar.length(); i > same; i--) queue.offer("key " + DELETE);
        String added = now.substring(same);
        for (int i = 0; i < added.length(); i += TYPE_CHUNK) {
            queue.offer("type " + added.substring(i, Math.min(added.length(), i + TYPE_CHUNK)));
        }
        typedSoFar = now;
    }

    private void pressEnter() {
        queue.offer("key " + ENTER);
        clearFieldQuietly();
    }

    /** The TV keeps what was typed; the field starts fresh, so its next change isn't taken as deleting it. */
    private void clearFieldQuietly() {
        silentChange = true;
        typeField.setText("");
        silentChange = false;
        typedSoFar = "";
    }

    private void setMode(int next) {
        boolean typing = next == MODE_TYPE, mouse = next == MODE_MOUSE, shows = next == MODE_SHOWS;
        if (typing && mode != MODE_TYPE) modeBefore = mode;
        mode = next;
        if (next == MODE_KEYS || mouse) saved.edit().putBoolean("mouseMode", mouse).apply();
        showsPanel.setVisibility(shows ? View.VISIBLE : View.GONE);
        if (shows) {
            queue.offer("watched");
            queue.offer("saved");
        }
        jarvisPanel.setVisibility(next == MODE_JARVIS ? View.VISIBLE : View.GONE);
        if (next == MODE_JARVIS) replyBar.setVisibility(View.GONE);
        typePanel.setVisibility(typing ? View.VISIBLE : View.GONE);
        remoteContent.setVisibility(next == MODE_KEYS ? View.VISIBLE : View.GONE);
        mouseContent.setVisibility(mouse ? View.VISIBLE : View.GONE);
        for (int i = 0; i < tabIcons.length; i++) {
            boolean on = i == next;
            tabIcons[i].setImageDrawable(new Icons(TAB_ICONS[i], on ? ON_ACCENT : MUTED, dp(22)));
            tabIcons[i].setBackground(on ? solid(15, ACCENT) : null);
            tabLabels[i].setTextColor(on ? INK : MUTED);
        }
        InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (typing) {
            clearFieldQuietly();
            typeField.requestFocus();
            // Twice, because the first try can land before the field is ready, and then the phone ignores it.
            for (long delay : new long[] {150, 500}) {
                typeField.postDelayed(() -> {
                    if (mode == MODE_TYPE) keyboard.showSoftInput(typeField, InputMethodManager.SHOW_FORCED);
                }, delay);
            }
        } else {
            hideTyping = false;
            applyFieldType();
            keyboard.hideSoftInputFromWindow(typeField.getWindowToken(), 0);
        }
    }

    /** The TV's own keyboard opened or closed: bring the phone's up, and put it away again if that's what opened it. */
    private void tvKeyboard(boolean open) {
        // A closed keyboard must be seen a few times running: while typing, a screen can change for a moment.
        closedPolls = open ? 0 : closedPolls + 1;
        if (!open && closedPolls < CLOSED_POLLS) return;
        if (open == tvKeyboardOpen) return;
        tvKeyboardOpen = open;
        if (!isPaired()) return;
        if (open && mode != MODE_TYPE) {
            openedByTv = true;
            setMode(MODE_TYPE);
        } else if (!open && openedByTv && mode == MODE_TYPE) {
            openedByTv = false;
            setMode(modeBefore);
        }
    }

    @Override public void onBackPressed() {
        if (mode == MODE_TYPE) {
            openedByTv = false;
            setMode(modeBefore);
        } else {
            super.onBackPressed();
        }
    }

    private int speed() { return Math.max(0, Math.min(SPEEDS.length - 1, saved.getInt("speed", 1))); }

    private void showSpeed() { speedButton.setText("Pointer: " + SPEED_NAMES[speed()]); }

    private void showMenu() {
        String[] items = {"Close the TV's debugging port", "Open it for app updates", "Forget this TV",
                "Jarvis speaks on: " + VOICE_NAMES[voicePlace()], "Share this app with another phone", "API keys"};
        new AlertDialog.Builder(this).setItems(items, (d, which) -> {
            if (which == 0) queue.offer("lock on");
            else if (which == 1) queue.offer("lock off");
            else if (which == 2) forget();
            else if (which == 4) shareApp();
            else if (which == 5) queue.offer("keys");
            else {
                stopSpeaking();
                saved.edit().putInt("voicePlace", (voicePlace() + 1) % VOICE_NAMES.length).apply();
                toastLine("Jarvis speaks on " + VOICE_NAMES[voicePlace()]);
            }
        }).show();
    }

    /** Keys go to the TV over the encrypted link and stay there. Blank fields leave what's there alone. */
    private void showKeys(String status) {
        runOnUiThread(() -> {
            LinearLayout box = column();
            box.setPadding(dp(20), dp(8), dp(20), dp(8));
            TextView help = label("Each person uses their own free keys. They stay on the TV.\n\nGemini: https://aistudio.google.com/apikey\nGroq: https://console.groq.com/keys\nTMDB: https://www.themoviedb.org/settings/api\nWatchmode: https://api.watchmode.com/", 14, MUTED);
            android.text.util.Linkify.addLinks(help, android.text.util.Linkify.WEB_URLS);
            box.addView(help);
            String[][] fields = {{"gemini", "Gemini key (for Jarvis)"}, {"groq", "Groq key (Jarvis backup)"}, {"tmdb", "TMDB key (for Shows)"}, {"watchmode", "Watchmode key (title links)"}, {"region", "Country, two letters"}};
            EditText[] inputs = new EditText[fields.length];
            for (int i = 0; i < fields.length; i++) {
                String now = "";
                for (String part : status.split(",")) {
                    String[] kv = part.split("=", 2);
                    if (kv.length == 2 && kv[0].equals(fields[i][0])) now = kv[0].equals("region") ? kv[1] : kv[1].equals("1") ? "set" : "not set";
                }
                inputs[i] = new EditText(this);
                inputs[i].setSingleLine(true);
                inputs[i].setTextSize(15);
                inputs[i].setTextColor(Color.WHITE);
                inputs[i].setHintTextColor(MUTED);
                inputs[i].setHint(fields[i][1] + (now.isEmpty() ? "" : " (" + now + ")"));
                if (!fields[i][0].equals("region")) inputs[i].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                box.addView(inputs[i], new LinearLayout.LayoutParams(-1, -2));
            }
            ScrollView scroll = new ScrollView(this);
            scroll.addView(box);
            new AlertDialog.Builder(this).setTitle("API keys").setView(scroll)
                    .setPositiveButton("Save", (d, which) -> {
                        for (int i = 0; i < fields.length; i++) {
                            String v = inputs[i].getText().toString().trim();
                            if (!v.isEmpty()) queue.offer("setkey " + fields[i][0] + " " + v);
                        }
                    })
                    .setNegativeButton("Cancel", null).show();
        });
    }

    /** The TV hands out the app itself, so this link works for any phone on the same Wi-Fi. */
    private void shareApp() {
        String link = "http://" + saved.getString("host", "") + ":" + APP_LINK_PORT;
        startActivity(android.content.Intent.createChooser(new android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_TEXT, "Get the My TV app (on our home Wi-Fi): " + link), "Share My TV"));
    }

    private void showPaired(boolean paired) {
        pairPage.setVisibility(paired ? View.GONE : View.VISIBLE);
        remotePage.setVisibility(paired ? View.VISIBLE : View.GONE);
    }

    private void show(String line, boolean connected) {
        runOnUiThread(() -> {
            status.setText(line);
            dot.setBackground(solid(4, connected ? 0xFF6DD58C : 0xFFE3B341));
        });
    }

    private void toastLine(String line) {
        runOnUiThread(() -> status.setText(line));
    }

    // MARK: - Pieces of the look

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        return lp;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        return lp;
    }

    private TextView label(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable solid(int radius, int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(radius));
        g.setColor(fill);
        return g;
    }

    /** A flat surface that lightens while pressed, like a key going down. */
    private StateListDrawable pressable(int radius, int fill) {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[] {android.R.attr.state_pressed}, solid(radius, PRESSED));
        d.addState(new int[] {}, solid(radius, fill));
        return d;
    }

    private TextView button(String s, float size) {
        TextView t = label(s, size, INK);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setBackground(pressable(26, SURFACE));
        return t;
    }

    private ImageView iconButton(String name) {
        ImageView b = new ImageView(this);
        b.setImageDrawable(new Icons(name, INK, dp(24)));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setClickable(true);
        b.setBackground(pressable(30, SURFACE));
        return b;
    }

    private ImageView iconKey(String name, int key) {
        ImageView b = iconButton(name);
        b.setOnClickListener(v -> { tick(v); queue.offer("key " + key); });
        return b;
    }

    /** Sends once on press, and keeps sending while held. */
    private <T extends View> T held(T view, String command) {
        final Runnable[] again = new Runnable[1];
        again[0] = () -> {
            if (queue.size() < 4) queue.offer(command);
            ui.postDelayed(again[0], REPEAT_MS);
        };
        view.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                tick(v);
                v.setPressed(true);
                queue.offer(command);
                ui.postDelayed(again[0], REPEAT_FIRST_MS);
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                v.setPressed(false);
                ui.removeCallbacks(again[0]);
            }
            return true;
        });
        return view;
    }

    /** A fixed-size round button centred in its share of a row. */
    private View slot(View button, int sizeDp) {
        FrameLayout f = new FrameLayout(this);
        f.addView(button, new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER));
        return f;
    }

    private LinearLayout.LayoutParams slotParams() { return new LinearLayout.LayoutParams(0, -1, 1); }

    private TextView tapping(String s, String command) {
        TextView t = button(s, 14);
        t.setOnClickListener(v -> { tick(v); queue.offer(command); });
        return t;
    }

    private void tick(View v) { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /**
     * The round pad, drawn in one piece like the real thing: the finger's direction from the middle picks the arrow, and
     * that quarter of the ring lights up. OK goes on release, so holding it can be a long press instead.
     */
    private final class DpadView extends View {
        private static final int ZONE_OK = 4;
        private static final long HOLD_MS = 600;
        private final android.graphics.Paint ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint ink = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF box = new android.graphics.RectF();
        private final android.graphics.Path chevron = new android.graphics.Path();
        private int held = -1;
        private boolean heldLong;
        private final Runnable again = new Runnable() {
            @Override public void run() {
                if (held < 0 || held == ZONE_OK) return;
                if (queue.size() < 4) queue.offer("key " + keyOf(held));
                ui.postDelayed(this, REPEAT_MS);
            }
        };
        private final Runnable longOk = () -> {
            if (held != ZONE_OK) return;
            heldLong = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            queue.offer("hold " + OK);
        };

        DpadView() {
            super(MainActivity.this);
            ink.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            ink.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            ink.setTextAlign(android.graphics.Paint.Align.CENTER);
        }

        private int keyOf(int zone) { return new int[] {UP, DOWN, LEFT, RIGHT, OK}[zone]; }

        private float radius() { return Math.min(getWidth(), getHeight()) / 2f - dp(2); }

        @Override protected void onDraw(android.graphics.Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = radius(), inner = r * 0.42f;
            ring.setStyle(android.graphics.Paint.Style.FILL);
            ring.setColor(SURFACE);
            c.drawCircle(cx, cy, r, ring);
            if (held >= 0 && held != ZONE_OK) {
                float[] from = {225, 45, 135, 315};
                box.set(cx - r, cy - r, cx + r, cy + r);
                ring.setColor(PRESSED);
                c.drawArc(box, from[held], 90, true, ring);
            }
            ring.setColor(BG);
            c.drawCircle(cx, cy, inner + dp(3), ring);
            ring.setColor(held == ZONE_OK ? PRESSED : RAISED);
            c.drawCircle(cx, cy, inner, ring);

            float at = (r + inner) / 2, arm = Math.min(r * 0.045f, dp(7));
            ink.setStyle(android.graphics.Paint.Style.STROKE);
            ink.setStrokeWidth(dp(3));
            ink.setColor(INK);
            for (int i = 0; i < 4; i++) {
                float dx = i == 2 ? -1 : i == 3 ? 1 : 0, dy = i == 0 ? -1 : i == 1 ? 1 : 0;
                float x = cx + dx * at, y = cy + dy * at;
                chevron.reset();
                chevron.moveTo(x - dx * arm + dy * arm * 2, y - dy * arm + dx * arm * 2);
                chevron.lineTo(x + dx * arm, y + dy * arm);
                chevron.lineTo(x - dx * arm - dy * arm * 2, y - dy * arm - dx * arm * 2);
                c.drawPath(chevron, ink);
            }
            ink.setStyle(android.graphics.Paint.Style.FILL);
            ink.setTextSize(dp(17));
            c.drawText("OK", cx, cy - (ink.ascent() + ink.descent()) / 2, ink);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                float dx = e.getX() - getWidth() / 2f, dy = e.getY() - getHeight() / 2f, r = radius();
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                if (distance > r) return false; // the corners around the circle aren't part of the pad
                int zone = distance < r * 0.42f ? ZONE_OK : Math.abs(dx) > Math.abs(dy) ? (dx < 0 ? 2 : 3) : (dy < 0 ? 0 : 1);
                held = zone;
                heldLong = false;
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (zone == ZONE_OK) {
                    ui.postDelayed(longOk, HOLD_MS);
                } else {
                    queue.offer("key " + keyOf(zone));
                    ui.postDelayed(again, REPEAT_FIRST_MS);
                }
                invalidate();
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                if (held == ZONE_OK && !heldLong && e.getAction() == MotionEvent.ACTION_UP) queue.offer("key " + OK);
                held = -1;
                ui.removeCallbacks(again);
                ui.removeCallbacks(longOk);
                invalidate();
            }
            return true;
        }
    }

    /** Drag to move the pointer, tap to click, two fingers to scroll. */
    private final class TouchpadView extends FrameLayout {
        private float lastX, lastY, startX, startY, scrollRest, restX, restY;
        private long downAt;
        private boolean moved2, twoFingers;

        TouchpadView() {
            super(MainActivity.this);
            setBackground(solid(24, SURFACE));
            TextView hint = label("Drag to move · Tap to click · Two fingers to scroll", 13, MUTED);
            hint.setGravity(Gravity.CENTER);
            addView(hint, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            hint.setPadding(dp(8), 0, dp(8), dp(10));
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float density = getResources().getDisplayMetrics().density;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = startX = e.getX();
                    lastY = startY = e.getY();
                    downAt = e.getEventTime();
                    moved2 = false;
                    twoFingers = false;
                    restX = restY = scrollRest = 0;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    twoFingers = true;
                    lastY = e.getY(0);
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getX() - lastX, dy = e.getY() - lastY;
                    lastX = e.getX();
                    lastY = e.getY();
                    if (Math.abs(e.getX() - startX) + Math.abs(e.getY() - startY) > 8 * density) moved2 = true;
                    if (twoFingers) {
                        scrollRest += dy / density / 14f;
                        int steps = (int) scrollRest;
                        if (steps != 0 && queue.size() < 4) {
                            scrollRest -= steps;
                            queue.offer("scroll " + steps);
                        }
                    } else {
                        float factor = SPEEDS[speed()] / density;
                        restX += dx * factor;
                        restY += dy * factor;
                        int mx = (int) restX, my = (int) restY;
                        if (mx != 0 || my != 0) {
                            restX -= mx;
                            restY -= my;
                            addMove(mx, my);
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (!moved2 && !twoFingers && e.getEventTime() - downAt < 300) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        queue.offer("click");
                    }
                    break;
                default:
                    break;
            }
            return true;
        }
    }

    /** Pointer moves add up while the worker is busy, so the pointer stays right behind the finger instead of lagging. */
    private void addMove(int dx, int dy) {
        synchronized (moved) {
            moveX += dx;
            moveY += dy;
            if (movePending) return;
            movePending = true;
        }
        queue.offer(MOUSE_MOVED);
    }

    private String takeMove() {
        synchronized (moved) {
            String s = "mouse " + moveX + " " + moveY;
            moveX = moveY = 0;
            movePending = false;
            return s;
        }
    }

    // MARK: - Apps

    private void showApps(List<String[]> apps) {
        runOnUiThread(() -> {
            appRow.removeAllViews();
            for (String[] app : apps) {
                LinearLayout tile = column();
                tile.setGravity(Gravity.CENTER);
                tile.setTag(app[0]);
                tile.setClickable(true);
                tile.setBackground(pressable(14, 0x00000000));
                ImageView icon = new ImageView(this);
                tile.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
                TextView name = label(app[1], 11, MUTED);
                name.setSingleLine(true);
                name.setGravity(Gravity.CENTER);
                tile.addView(name, new LinearLayout.LayoutParams(-1, -2));
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tile.setPadding(dp(2), dp(4), dp(2), dp(2));
                tile.setOnClickListener(v -> { tick(v); queue.offer("open " + app[0]); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(68), ViewGroup.LayoutParams.MATCH_PARENT);
                appRow.addView(tile, lp);
            }
        });
    }

    private void showIcon(String pkg, Bitmap b) {
        runOnUiThread(() -> {
            View tile = appRow.findViewWithTag(pkg);
            if (tile instanceof LinearLayout) {
                ((ImageView) ((LinearLayout) tile).getChildAt(0)).setImageDrawable(new BitmapDrawable(getResources(), b));
            }
        });
    }

    // MARK: - Connection

    @Override protected void onStart() {
        super.onStart();
        startWorker();
        prepareSpeech();
    }

    @Override protected void onStop() {
        super.onStop();
        stopListening();
        stopSpeaking();
        if (tts != null) {
            tts.shutdown();
            tts = null;
            ttsReady = false;
        }
        // Nothing carries on once the app is out of sight: no connection, no waiting answers, no commands saved for later.
        queue.clear();
        jarvisAskedAt = 0;
        waitingForAnswer = false;
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
        Socket open = liveSocket;
        if (open != null) new Thread(() -> close(open)).start(); // it may be waiting on the TV; closing can't wait on the screen's thread
    }

    private void startWorker() {
        Thread t = new Thread(this::connectLoop);
        worker = t;
        t.start();
    }

    private boolean current() { return Thread.currentThread() == worker; }

    private void connectLoop() {
        long wait = 1000;
        while (current()) {
            if (!isPaired()) {
                if (!sleep(500)) return;
                continue;
            }
            String id = saved.getString("id", ""), host = saved.getString("host", "");
            byte[] secret = unhex(saved.getString("secret", ""));
            try {
                show("Connecting…", false);
                Session session = open(host, id, secret);
                if (session == null) {
                    show("Looking for the TV…", false);
                    host = find();
                    if (host == null) throw new IOException("TV not found");
                    saved.edit().putString("host", host).apply();
                    session = open(host, id, secret);
                    if (session == null) throw new IOException("the TV didn't accept this phone");
                }
                wait = 1000;
                serve(session);
            } catch (IOException e) {
                show("Not connected: " + e.getMessage(), false);
                if (!sleep(wait)) return;
                wait = Math.min(wait * 2, 8000);
            }
        }
    }

    private static final class Session {
        final Socket socket;
        final Wire.Channel channel;
        Session(Socket socket, Wire.Channel channel) { this.socket = socket; this.channel = channel; }
    }

    private Session open(String host, String id, byte[] secret) {
        if (host.isEmpty()) return null;
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(50_000); // a search that needs the AI's help can take the TV a while
            s.connect(new InetSocketAddress(host, Wire.PORT), 3000);
            return new Session(s, Wire.connect(s, id, secret));
        } catch (IOException e) {
            close(s);
            return null;
        }
    }

    /** Commands from the queue. When idle, asks each second if the TV's keyboard is open, which also shows a lost connection. */
    private void serve(Session session) throws IOException {
        liveSocket = session.socket;
        try {
            String info = call(session, "info");
            show(describe(info), true);
            offerUpdate(call(session, "phoneversion"));
            loadApps(session);
            long lastInfo = System.currentTimeMillis();
            while (current()) {
                boolean thinking = jarvisAskedAt != 0;
                String command = queue.poll(thinking ? JARVIS_POLL_MS : IDLE_POLL_MS, TimeUnit.MILLISECONDS);
                if (command == null && thinking) {
                    String reply = call(session, "jarvisget");
                    if (reply.startsWith("jarvis ") && !reply.equals("jarvis wait")) {
                        jarvisAskedAt = 0;
                        jarvisAnswer(reply.substring(7));
                    } else if (System.currentTimeMillis() - jarvisAskedAt > JARVIS_GIVE_UP_MS) {
                        jarvisAskedAt = 0;
                        jarvisAnswer("{\"say\":\"The TV took too long to answer. Please try again.\"}");
                    }
                    continue;
                }
                if (command == null) {
                    boolean open = "kb 1".equals(call(session, "kb"));
                    runOnUiThread(() -> tvKeyboard(open));
                    if (System.currentTimeMillis() - lastInfo > 15_000) {
                        show(describe(call(session, "info")), true);
                        lastInfo = System.currentTimeMillis();
                    }
                    continue;
                }
                if (UPDATE.equals(command)) {
                    downloadUpdate(session);
                    continue;
                }
                if (MOUSE_MOVED.equals(command)) command = takeMove();
                String answer = call(session, command);
                if (answer.startsWith("err ")) toastLine(answer.substring(4));
                else showsAnswer(answer);
            }
        } catch (InterruptedException e) {
            // Leaving the screen, or forgetting the TV.
        } finally {
            close(session.socket);
        }
    }

    /** The TV carries the newest phone app, so updating doesn't need a computer. */
    private void offerUpdate(String answer) {
        if (!answer.startsWith("phoneversion ")) return;
        try {
            long onTv = Long.parseLong(answer.substring(13).trim());
            long here = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            if (onTv > here) runOnUiThread(() -> updateBar.setVisibility(View.VISIBLE));
        } catch (NumberFormatException | android.content.pm.PackageManager.NameNotFoundException e) {
            // Nothing to offer.
        }
    }

    private void downloadUpdate(Session session) throws IOException {
        java.io.File file = new java.io.File(getCacheDir(), ApkProvider.FILE);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            long got = 0, total = 1;
            while (got < total) {
                String answer = call(session, "phoneapk " + got);
                String[] p = answer.split(" ", 3);
                if (!answer.startsWith("apk ") || p.length < 3) {
                    toastLine("Couldn't get the update from the TV. Try again later");
                    return;
                }
                total = Long.parseLong(p[1]);
                byte[] part = Base64.decode(p[2], Base64.NO_WRAP);
                if (part.length == 0) break;
                out.write(part);
                got += part.length;
                toastLine("Getting the update: " + (100 * got / total) + "%");
            }
        }
        toastLine("Connected to TV");
        android.net.Uri uri = android.net.Uri.parse("content://" + ApkProvider.AUTHORITY + "/" + ApkProvider.FILE);
        startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void loadApps(Session session) throws IOException {
        String answer = call(session, "apps");
        if (!answer.startsWith("apps ")) return;
        List<String[]> apps = new ArrayList<>();
        for (String line : answer.substring(5).split("\n")) {
            String[] p = line.split("\t", 2);
            if (p.length == 2) {
                apps.add(p);
                tvApps.put(p[1].toLowerCase(java.util.Locale.ROOT), p[0]);
            }
        }
        showApps(apps);
        for (String[] app : apps) {
            String icon = call(session, "icon " + app[0]);
            if (!icon.startsWith("icon ")) continue;
            byte[] png = Base64.decode(icon.substring(5), Base64.NO_WRAP);
            Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (b != null) showIcon(app[0], b);
        }
    }

    private String call(Session s, String command) throws IOException {
        s.channel.send(command);
        return s.channel.receive();
    }

    private String describe(String info) {
        String[] p = info.startsWith("info ") ? info.substring(5).split("\\|") : new String[0];
        if (p.length < 3) return "Connected";
        return p[2].equals("ready") ? "Connected to TV" : "Connected, keys starting…";
    }

    /** Asks the network where the TV is, so a changed address doesn't matter. */
    private String find() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(700);
            byte[] ask = Wire.ASK.getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < 4; i++) {
                for (InetAddress to : broadcasts()) s.send(new DatagramPacket(ask, ask.length, to, Wire.DISCOVERY_PORT));
                try {
                    byte[] b = new byte[128];
                    DatagramPacket p = new DatagramPacket(b, b.length);
                    s.receive(p);
                    if (new String(b, 0, p.getLength(), StandardCharsets.UTF_8).startsWith(Wire.ANSWER))
                        return p.getAddress().getHostAddress();
                } catch (java.net.SocketTimeoutException tryAgain) {
                    // Next round.
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    private InetAddress[] broadcasts() throws IOException {
        InetAddress all = InetAddress.getByName("255.255.255.255");
        WifiManager w = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        DhcpInfo d = w == null ? null : w.getDhcpInfo();
        if (d == null || d.ipAddress == 0) return new InetAddress[] {all};
        int b = (d.ipAddress & d.netmask) | ~d.netmask;
        return new InetAddress[] {all, InetAddress.getByAddress(new byte[] {(byte) b, (byte) (b >> 8), (byte) (b >> 16), (byte) (b >> 24)})};
    }

    // MARK: - Pairing

    private void startPairing() {
        final String code = codeField.getText().toString().trim();
        if (code.length() != 6) {
            codeField.setError("The code has 6 digits");
            return;
        }
        new Thread(() -> {
            String host = find();
            if (host == null) {
                pairError("TV not found. Check the phone and the TV are on the same Wi-Fi.");
                return;
            }
            Socket s = new Socket();
            try {
                s.setSoTimeout(15_000);
                s.connect(new InetSocketAddress(host, Wire.PORT), 4000);
                Wire.Paired p = Wire.pair(s, code, Build.MODEL);
                if (p == null) {
                    pairError("Wrong or expired code. Show a new one on the TV.");
                    return;
                }
                saved.edit().putString("id", p.id).putString("secret", hex(p.secret)).putString("host", host).commit();
                runOnUiThread(() -> showPaired(true));
            } catch (IOException e) {
                pairError("Couldn't reach the TV: " + e.getMessage());
            } finally {
                close(s);
            }
        }).start();
    }

    private void pairError(String message) {
        runOnUiThread(() -> codeField.setError(message));
    }

    private boolean isPaired() { return saved.contains("secret"); }

    private void forget() {
        saved.edit().remove("id").remove("secret").remove("host").commit();
        queue.clear();
        appRow.removeAllViews();
        Thread old = worker;
        startWorker();
        if (old != null) old.interrupt();
        showPaired(false);
    }

    // MARK: - Small things

    private boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return current();
        } catch (InterruptedException e) {
            return false;
        }
    }

    private static void close(Socket s) {
        try { s.close(); } catch (IOException ignored) {}
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(java.util.Locale.US, "%02x", x));
        return s.toString();
    }

    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}
