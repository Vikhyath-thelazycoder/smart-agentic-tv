package io.github.smartagentictv.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/** The glass volume capsule: drawn with no blur, which this TV's graphics chip can't do live. */
final class VolumeView extends View {
    static final int W = 460, H = 76;
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG),
            track = new Paint(Paint.ANTI_ALIAS_FLAG), bar = new Paint(Paint.ANTI_ALIAS_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float level;
    private boolean muted;

    VolumeView(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        glass.setShader(new LinearGradient(0, 0, 0, H, 0xE6343440, 0xE61A1A22, Shader.TileMode.CLAMP));
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.5f);
        edge.setShader(new LinearGradient(0, 0, 0, H, 0x8CFFFFFF, 0x26FFFFFF, Shader.TileMode.CLAMP));
        track.setColor(0x33FFFFFF);
        ink.setColor(0xFFFFFFFF);
        ink.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ink.setTextSize(24);
    }

    void show(float level, boolean muted) {
        this.level = level;
        this.muted = muted;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        r.set(1, 1, W - 1, H - 1);
        float round = (H - 2) / 2f;
        c.drawRoundRect(r, round, round, glass);
        c.drawRoundRect(r, round, round, edge);
        speaker(c);
        float left = 84, right = W - 92, cy = H / 2f;
        r.set(left, cy - 6, right, cy + 6);
        c.drawRoundRect(r, 6, 6, track);
        float fill = muted ? 0 : level;
        if (fill > 0) {
            bar.setColor(Daily.accent());
            r.set(left, cy - 6, Math.max(left + 12, left + (right - left) * fill), cy + 6);
            c.drawRoundRect(r, 6, 6, bar);
        }
        String s = muted ? "Mute" : String.valueOf(Math.round(level * 100));
        c.drawText(s, right + 16, cy + 9, ink);
    }

    private void speaker(Canvas c) {
        float x = 34, cy = H / 2f;
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(x, cy - 6);
        p.lineTo(x + 8, cy - 6);
        p.lineTo(x + 18, cy - 15);
        p.lineTo(x + 18, cy + 15);
        p.lineTo(x + 8, cy + 6);
        p.lineTo(x, cy + 6);
        p.close();
        c.drawPath(p, ink);
        if (muted) return;
        Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        arc.setColor(0xFFFFFFFF);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(3);
        arc.setStrokeCap(Paint.Cap.ROUND);
        r.set(x + 12, cy - 11, x + 30, cy + 11);
        c.drawArc(r, -50, 100, false, arc);
    }
}
