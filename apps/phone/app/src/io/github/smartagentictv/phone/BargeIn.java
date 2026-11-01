package io.github.smartagentictv.phone;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;

/**
 * Listens while the phone is speaking, only for loudness, and says when someone starts talking over it. The sound is
 * never kept or sent. It uses the call microphone, whose echo cancelling keeps the phone's own voice from setting it off.
 */
final class BargeIn {
    private static final int RATE = 16000, FRAME = 480, SETTLE_FRAMES = 12, LOUD_FRAMES = 8;
    private static final double ABOVE_BACKGROUND = 3.5, QUIETEST_VOICE = 1400;

    private final Runnable onVoice;
    private volatile boolean running;

    BargeIn(Runnable onVoice) { this.onVoice = onVoice; }

    void start() {
        running = true;
        Thread t = new Thread(this::listen, "barge-in");
        t.setDaemon(true);
        t.start();
    }

    void stop() { running = false; }

    private void listen() {
        AudioRecord mic = null;
        AcousticEchoCanceler echo = null;
        try {
            int size = Math.max(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), FRAME * 4);
            mic = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
            if (mic.getState() != AudioRecord.STATE_INITIALIZED) return;
            if (AcousticEchoCanceler.isAvailable()) {
                echo = AcousticEchoCanceler.create(mic.getAudioSessionId());
                if (echo != null) echo.setEnabled(true);
            }
            mic.startRecording();
            short[] frame = new short[FRAME];
            double background = 0;
            int seen = 0, loud = 0;
            while (running) {
                int got = mic.read(frame, 0, FRAME);
                if (got <= 0) return;
                double level = level(frame, got);
                if (seen < SETTLE_FRAMES) {
                    background = (background * seen + level) / (seen + 1);
                    seen++;
                    continue;
                }
                loud = level > Math.max(QUIETEST_VOICE, background * ABOVE_BACKGROUND) ? loud + 1 : 0;
                if (loud >= LOUD_FRAMES && running) {
                    running = false;
                    onVoice.run();
                }
            }
        } catch (RuntimeException e) {
            // No microphone to spare: tapping the mic still interrupts.
        } finally {
            if (echo != null) echo.release();
            if (mic != null) {
                try {
                    mic.stop();
                } catch (IllegalStateException ignored) {
                    // It never started.
                }
                mic.release();
            }
        }
    }

    private static double level(short[] frame, int count) {
        long sum = 0;
        for (int i = 0; i < count; i++) sum += frame[i] * frame[i];
        return Math.sqrt(sum / (double) count);
    }
}
