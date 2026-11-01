package io.github.smartagentictv.tv;

import android.app.Application;

/** Gives Prefs a context in every process. */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Keys.init(this);
        Prefs.init(this);
    }
}
