package io.github.smartagentictv.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The everyday settings. Anything deeper still opens the TV's own Settings, which can't be restyled. */
public class GlassSettingsActivity extends GlassActivity {
    private View wifi, volume, voice;
    private AudioManager audio;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Voice.prepare(this);
        title("Settings", "The everyday things.\nDeeper options open the TV's own settings.");
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        wifi = row("Wi-Fi", "", () -> startActivity(new Intent(this, WifiActivity.class)));
        volume = row("Volume", "", () -> {});
        volume.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        code == KeyEvent.KEYCODE_DPAD_RIGHT ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
                refresh();
                return true;
            }
            return false;
        });
        voice = row("Idle screen voice", "", () -> {
            IdleVoice.setEnabled(this, !IdleVoice.enabled(this));
            refresh();
            // Turned on: say one line, so you hear what it sounds like.
            if (IdleVoice.enabled(this)) {
                if (!Voice.isReady()) toast("Getting the voice ready: it speaks in a moment");
                Voice.say(this, Quotes.now().text);
            } else {
                Voice.quiet();
            }
        });
        row("Test the voice", "Plays one line through your speaker, after about 3 seconds", () -> {
            if (!Voice.isReady()) toast("Getting the voice ready: it speaks in a moment");
            Voice.say(this, Quotes.now().text);
        });
        row("Phone", "Pair your phone to control this TV", () -> startActivity(new Intent(this, PairActivity.class)));
        row("Bluetooth", "Remotes, speakers and headphones", () -> open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Bluetooth"));
        row("Sound", "Speakers and sound options", () -> open(new Intent(Settings.ACTION_SOUND_SETTINGS), "Sound"));
        row("Display", "Screen options", () -> open(new Intent(Settings.ACTION_DISPLAY_SETTINGS), "Display"));
        String boardSettings = Board.get(this).settingsActivity();
        if (!boardSettings.isEmpty()) {
            row("TV board settings", "Network, time and general options", () -> open(new Intent().setComponent(ComponentName.unflattenFromString(boardSettings))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "TV board settings"));
        }
        row("Date and time", "", () -> open(new Intent(Settings.ACTION_DATE_SETTINGS), "Date and time"));
        row("Apps", "", () -> open(new Intent(Settings.ACTION_APPLICATION_SETTINGS), "Apps"));
        row("Home screen options", "Shelf, clock and weather", () -> startActivity(new Intent(this, SettingsActivity.class)));
        row("All TV settings", "The TV's original settings", () -> open(new Intent(Settings.ACTION_SETTINGS), "TV settings"));
        note("Android " + Build.VERSION.SDK_INT + " · " + Build.MODEL);
        focusFirst();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        sub(wifi, ssid(this));
        int now = audio.getStreamVolume(AudioManager.STREAM_MUSIC), max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        sub(volume, Math.round(100f * now / max) + "%   ·   use ◀ ▶ to change");
        sub(voice, IdleVoice.enabled(this) ? "On: reads a quote every 15 minutes" : "Off");
    }

    private void sub(View row, String s) {
        LinearLayout r = (LinearLayout) row;
        TextView t;
        if (r.getChildCount() > 1) {
            t = (TextView) r.getChildAt(1);
        } else {
            t = text("", 16, 0xB3FFFFFF, Ui.regular);
            t.setPadding(0, 3, 0, 0);
            t.setSingleLine(true);
            r.addView(t);
            final TextView title = (TextView) r.getChildAt(0);
            r.setOnFocusChangeListener((v, focused) -> {
                title.setTextColor(focused ? 0xFF0B0B10 : 0xFFFFFFFF);
                t.setTextColor(focused ? 0xCC0B0B10 : 0xB3FFFFFF);
            });
            if (r.isFocused()) {
                title.setTextColor(0xFF0B0B10);
                t.setTextColor(0xCC0B0B10);
            }
        }
        t.setText(s);
    }

    static String ssid(Context c) {
        WifiManager w = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (w == null || !w.isWifiEnabled()) return "Off";
        WifiInfo info = w.getConnectionInfo();
        String s = info == null ? null : info.getSSID();
        if (s == null || s.isEmpty() || "<unknown ssid>".equals(s)) return "Not connected";
        return s.replace("\"", "");
    }
}
