package io.github.smartagentictv.tv;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/** The idle screen: the home screen opens it after 5 minutes with no key pressed. Any key closes it. */
public class IdleActivity extends Activity {
    private DreamView view;
    private IdleVoice voice;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new DreamView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.start();
        voice = new IdleVoice(this);
        voice.start();
    }

    @Override protected void onPause() {
        if (voice != null) voice.stop();
        voice = null;
        view.stop();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }
}
