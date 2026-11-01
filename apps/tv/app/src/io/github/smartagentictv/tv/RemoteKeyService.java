package io.github.smartagentictv.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Shows the glass volume capsule in place of the TV's own bar, and sends the board's old home screen back to this one
 * (its firmware turns the old one on again and opens it at every start). Reads only the volume keys and which screen
 * the old home opened: no other app's content. The system waits for it on every key, so it does next to nothing.
 */
public class RemoteKeyService extends AccessibilityService {
    private static final String PERMISSION = "io.github.smartagentictv.tv.permission.INTERNAL";
    static final String ACTION_VOLUME = "io.github.smartagentictv.tv.action.VOLUME", EXTRA_KEY = "key";
    private static final long HIDE_MS = 2200, REPEAT_FIRST_MS = 450, REPEAT_MS = 110;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager audio;
    private WindowManager windows;
    private VolumeView capsule;
    private int holding;
    private long lastHome;

    private final Runnable hide = () -> {
        if (capsule != null) capsule.animate().alpha(0).setDuration(220).start();
    };
    private final Runnable repeat = new Runnable() {
        @Override public void run() {
            if (holding == 0) return;
            change(holding);
            handler.postDelayed(this, REPEAT_MS);
        }
    };

    /** Volume from the phone link, so it shows the same glass bar as the remote. Only this app can send this. */
    private final BroadcastReceiver phoneVolume = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            int key = i.getIntExtra(EXTRA_KEY, 0);
            if (key == KeyEvent.KEYCODE_VOLUME_UP) change(AudioManager.ADJUST_RAISE);
            else if (key == KeyEvent.KEYCODE_VOLUME_DOWN) change(AudioManager.ADJUST_LOWER);
            else if (key == KeyEvent.KEYCODE_VOLUME_MUTE) change(AudioManager.ADJUST_TOGGLE_MUTE);
        }
    };

    @Override public void onDestroy() {
        try {
            unregisterReceiver(phoneVolume);
        } catch (IllegalArgumentException e) {
            // It never got as far as registering.
        }
        super.onDestroy();
    }

    @Override protected void onServiceConnected() {
        registerReceiver(phoneVolume, new android.content.IntentFilter(ACTION_VOLUME), PERMISSION, null);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        windows = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        // At startup the old home can open before this service runs, so its window is never seen here.
        if (!Board.get(this).oldHome().isEmpty() && android.os.SystemClock.elapsedRealtime() < 180_000) goHome();
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            int dir = code == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                // Our own repeat: some firmware stops sending repeats once a key filter is on.
                if (e.getRepeatCount() == 0) {
                    holding = dir;
                    change(dir);
                    handler.removeCallbacks(repeat);
                    handler.postDelayed(repeat, REPEAT_FIRST_MS);
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                holding = 0;
                handler.removeCallbacks(repeat);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) change(AudioManager.ADJUST_TOGGLE_MUTE);
            return true;
        }
        return RemoteKeys.onKey(this, e);
    }

    private void change(int dir) {
        if (audio == null) return;
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0);
        int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        float level = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
        show(level, audio.isStreamMute(AudioManager.STREAM_MUSIC));
    }

    private void show(float level, boolean muted) {
        if (capsule == null) {
            capsule = new VolumeView(this);
            capsule.setAlpha(0);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(VolumeView.W, VolumeView.H,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = 34;
            windows.addView(capsule, lp);
        }
        capsule.show(level, muted);
        capsule.animate().alpha(1).setDuration(120).start();
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, HIDE_MS);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        String oldHome = Board.get(this).oldHome();
        if (pkg == null || oldHome.isEmpty() || !oldHome.contentEquals(pkg)) return;
        long now = System.currentTimeMillis();
        if (now - lastHome < 1500) return;
        lastHome = now;
        goHome();
    }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(new ComponentName(this, HomeActivity.class)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(home);
        } catch (RuntimeException e) {
            AppLog.w("back to the new home: " + e.getMessage());
        }
    }

    @Override public void onInterrupt() {}
}
