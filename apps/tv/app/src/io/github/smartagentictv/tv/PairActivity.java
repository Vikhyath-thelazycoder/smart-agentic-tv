package io.github.smartagentictv.tv;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Pair a phone: shows the code to type on the phone, and whether the TV's debugging port is closed to the network. */
public class PairActivity extends GlassActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView codeView, status;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinkService.start(this);
        title("Phone", "Pair your phone to control this TV.");
        codeView = text("", 76, Color.WHITE, Ui.light);
        codeView.setGravity(Gravity.CENTER);
        codeView.setPadding(0, 12, 0, 12);
        rows.addView(codeView, new LinearLayout.LayoutParams(-1, -2));
        status = text("", 18, 0xCCFFFFFF, Ui.regular);
        status.setPadding(26, 6, 26, 12);
        rows.addView(status);
        View pair = row("Show a pairing code", "Type it in the phone app. It lasts 2 minutes.", () -> {
            LinkService s = LinkService.get();
            if (s != null) s.startPairing();
            refresh();
        });
        row("Forget paired phones", "They will need a new code", () -> {
            LinkService s = LinkService.get();
            if (s != null) {
                s.forgetPhones();
                toast("Forgotten");
            }
            refresh();
        });
        row("Debugging port", "Close it, or open it for app updates", () -> {
            LinkService s = LinkService.get();
            if (s != null) s.setLocked(!s.locked());
            refresh();
        });
        pair.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        LinkService s = LinkService.get();
        if (s != null) s.stopPairing();
    }

    private void refresh() {
        LinkService s = LinkService.get();
        if (s == null) {
            status.setText("Starting…");
            return;
        }
        String code = s.currentCode();
        codeView.setText(code == null ? "" : code.substring(0, 3) + " " + code.substring(3));
        codeView.setVisibility(code == null ? View.GONE : View.VISIBLE);
        String link = AppDownload.link();
        status.setText("Paired phones: " + s.phoneCount() + "   ·   Debugging port: " + s.netState()
                + (link == null ? "" : "\nAnother phone on this Wi-Fi can get the app at " + link));
    }
}
