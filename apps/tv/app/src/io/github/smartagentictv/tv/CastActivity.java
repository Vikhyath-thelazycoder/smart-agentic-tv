package io.github.smartagentictv.tv;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

/** The way into casting. The Miracast screen itself belongs to the board's own app, which can't be restyled. */
public class CastActivity extends GlassActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        title("Miracast", "Show your phone's screen on the TV.");
        row("Start Miracast", "Then choose this TV on your phone", () -> open(new Intent().setComponent(
                ComponentName.unflattenFromString(Board.get(this).miracastActivity().isEmpty() ? "android/.None" : Board.get(this).miracastActivity()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Miracast"));
        String name = Settings.Global.getString(getContentResolver(), "device_name");
        note("1.  Press Start Miracast above.\n\n"
                + "2.  On your Android phone, open Settings and search for Cast, Smart View or Screen Mirroring.\n\n"
                + "3.  Choose " + (name == null || name.isEmpty() ? "this TV" : name) + " from the list.\n\n"
                + "Press Back on the remote to stop.");
        focusFirst();
    }
}
