package io.github.smartagentictv.tv;

import android.util.Log;

/** The app's log (adb logcat -s SmartAgenticTV). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("SmartAgenticTV", message);
    }
}
