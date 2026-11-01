package io.github.smartagentictv.tv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/**
 * The one English voice. Google's engine needs half a minute to load on this TV, so it is loaded ahead of time and kept
 * ready; a line asked for before then is spoken as soon as it can be.
 */
final class Voice {
    private Voice() {}

    private static final long WAKE_MS = 3000, MAX_AWAKE_MS = 60_000;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static volatile boolean awake;
    private static TextToSpeech tts;
    private static boolean ready;
    private static String waiting;

    static synchronized void prepare(Context c) {
        if (tts != null) return;
        tts = new TextToSpeech(c.getApplicationContext(), status -> {
            synchronized (Voice.class) {
                ready = status == TextToSpeech.SUCCESS;
                AppLog.w("voice: engine status " + status);
                if (!ready) {
                    tts = null;
                    return;
                }
                int language = tts.setLanguage(Locale.US);
                AppLog.w("voice: language " + language);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { AppLog.w("voice: speaking " + id); }
                    @Override public void onDone(String id) {
                        AppLog.w("voice: finished " + id);
                        if ("line".equals(id)) handler.postDelayed(Voice::letSpeakerSleep, 800);
                    }
                    @Override public void onError(String id) {
                        AppLog.w("voice: error " + id);
                        if ("line".equals(id)) letSpeakerSleep();
                    }
                });
                // A silent word makes the engine load the voice now, not when it is first needed.
                Bundle silent = new Bundle();
                silent.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0f);
                tts.speak("ready", TextToSpeech.QUEUE_FLUSH, silent, "warm");
                if (waiting != null) {
                    speak(waiting, TextToSpeech.QUEUE_ADD);
                    waiting = null;
                }
            }
        }, "com.google.android.tts");
    }

    /** An HDMI speaker sleeps through silence and misses the first seconds: wake it before the line. */
    private static void speak(final String line, int mode) {
        tts.stop();
        keepSpeakerAwake();
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> {
            synchronized (Voice.class) {
                if (tts != null) tts.speak(line, TextToSpeech.QUEUE_FLUSH, null, "line");
            }
        }, WAKE_MS);
        handler.postDelayed(Voice::letSpeakerSleep, MAX_AWAKE_MS);   // never left running
    }

    /** Sound too faint to hear, so the TV's output and the HDMI link are already running when the words start. */
    private static void keepSpeakerAwake() {
        if (awake) return;
        awake = true;
        new Thread(() -> {
            AudioTrack track = null;
            try {
                int rate = 48000, min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setSampleRate(rate).build())
                        .setBufferSizeInBytes(min * 2).setTransferMode(AudioTrack.MODE_STREAM).build();
                track.play();
                short[] faint = new short[4800];
                java.util.Random noise = new java.util.Random();
                while (awake) {
                    for (int i = 0; i < faint.length; i++) faint[i] = (short) (noise.nextInt(5) - 2);
                    track.write(faint, 0, faint.length);
                }
            } catch (RuntimeException e) {
                AppLog.w("voice: couldn't open the speaker: " + e.getMessage());
            } finally {
                if (track != null) {
                    try {
                        track.stop();
                    } catch (RuntimeException ignored) {
                    }
                    track.release();
                }
            }
        }, "speaker-awake").start();
    }

    private static void letSpeakerSleep() {
        awake = false;
    }

    static synchronized boolean isReady() {
        return ready;
    }

    static synchronized void say(Context c, String line) {
        prepare(c);
        if (ready) speak(line, TextToSpeech.QUEUE_FLUSH);
        else waiting = line;
    }

    static synchronized void quiet() {
        waiting = null;
        handler.removeCallbacksAndMessages(null);
        letSpeakerSleep();
        if (ready) tts.stop();
    }
}
