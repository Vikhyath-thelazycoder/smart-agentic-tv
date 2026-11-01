package io.github.smartagentictv.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** The look shared by the Settings, Wi-Fi, Media and Miracast screens: today's wallpaper with a smoked glass panel. */
abstract class GlassActivity extends Activity {
    protected LinearLayout rows;
    private TextView heading, subheading;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        FrameLayout root = new FrameLayout(this);
        ImageView wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wall.setImageBitmap(Daily.wallpaper(this));
        root.addView(wall, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setPadding(56, 44, 56, 44);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        left.setPadding(0, 0, 40, 0);
        heading = text("", 44, Color.WHITE, Ui.light);
        subheading = text("", 18, 0xB3FFFFFF, Ui.regular);
        subheading.setPadding(0, 14, 0, 0);
        left.addView(heading);
        left.addView(subheading);
        page.addView(left, new LinearLayout.LayoutParams(360, -1));

        FrameLayout panel = new FrameLayout(this);
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x47FFFFFF, 0x24FFFFFF});
        glass.setCornerRadius(32);
        glass.setStroke(1, 0x4DFFFFFF);
        panel.setBackground(glass);
        panel.setPadding(4, 12, 4, 12);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(14, 10, 14, 10);
        rows.setClipChildren(false);
        scroll.addView(rows);
        panel.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        page.addView(panel, new LinearLayout.LayoutParams(0, -1, 1));
        setContentView(root);
    }

    protected void title(String title, String sub) {
        heading.setText(title);
        subheading.setText(sub);
    }

    protected TextView text(String s, float size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        t.setTextColor(color);
        if (face != null) t.setTypeface(face);
        return t;
    }

    /** A row the remote can focus: the focused one fills with today's accent color and grows a little. */
    protected View row(String title, String sub, Runnable onClick) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(26, 15, 26, 15);
        r.setFocusable(true);
        r.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(20);
        on.setColor(Daily.accent());
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(20);
        off.setColor(0x14FFFFFF);
        bg.addState(new int[] {android.R.attr.state_focused}, on);
        bg.addState(new int[] {}, off);
        r.setBackground(bg);
        final TextView a = text(title, 23, Color.WHITE, Ui.medium);
        a.setSingleLine(true);
        r.addView(a);
        TextView b = null;
        if (sub != null && !sub.isEmpty()) {
            b = text(sub, 16, 0xB3FFFFFF, Ui.regular);
            b.setSingleLine(true);
            b.setPadding(0, 3, 0, 0);
            r.addView(b);
        }
        final TextView subtitle = b;
        r.setOnFocusChangeListener((v, focused) -> {
            int ink = focused ? 0xFF0B0B10 : Color.WHITE;
            a.setTextColor(ink);
            if (subtitle != null) subtitle.setTextColor(focused ? 0xCC0B0B10 : 0xB3FFFFFF);
        });
        r.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 5, 0, 5);
        rows.addView(r, lp);
        return r;
    }

    protected void note(String s) {
        TextView t = text(s, 18, 0xCCFFFFFF, Ui.regular);
        t.setPadding(26, 18, 26, 18);
        rows.addView(t);
    }

    protected void focusFirst() {
        for (int i = 0; i < rows.getChildCount(); i++) {
            View v = rows.getChildAt(i);
            if (v.isFocusable()) {
                v.requestFocus();
                return;
            }
        }
    }

    protected void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    protected void open(Intent intent, String what) {
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            AppLog.w("couldn't open " + what + ": " + e.getMessage());
            toast("Couldn't open " + what);
        }
    }
}
