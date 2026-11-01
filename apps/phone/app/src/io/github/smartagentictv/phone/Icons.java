package io.github.smartagentictv.phone;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** The app's icons, drawn as lines on a 24-unit square so they stay sharp at any size and match each other. */
final class Icons extends Drawable {
    private static final float STROKE = 1.9f;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path strokes = new Path(), solids = new Path();
    private final int size;

    Icons(String name, int color, int sizePx) {
        size = sizePx;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        draw(name);
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }

    @Override public void draw(Canvas c) {
        int saved = c.save();
        c.translate(getBounds().left, getBounds().top);
        c.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        c.drawPath(solids, fill);
        c.drawPath(strokes, line);
        c.restoreToCount(saved);
    }

    @Override public void setAlpha(int alpha) {
        line.setAlpha(alpha);
        fill.setAlpha(alpha);
    }

    @Override public void setColorFilter(ColorFilter f) {
        line.setColorFilter(f);
        fill.setColorFilter(f);
    }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    private void draw(String name) {
        Path p = strokes;
        switch (name) {
            case "power":
                p.moveTo(12, 3.5f); p.lineTo(12, 11.5f);
                p.addArc(new RectF(4.5f, 4.5f, 19.5f, 19.5f), -50, 280);
                break;
            case "back":
                p.moveTo(10, 6); p.lineTo(4.5f, 11.5f); p.lineTo(10, 17);
                p.moveTo(4.5f, 11.5f); p.lineTo(14, 11.5f);
                p.quadTo(19.5f, 11.5f, 19.5f, 16); p.lineTo(19.5f, 18);
                break;
            case "home":
                p.moveTo(4, 11); p.lineTo(12, 4); p.lineTo(20, 11);
                p.moveTo(6.5f, 9.5f); p.lineTo(6.5f, 20); p.lineTo(17.5f, 20); p.lineTo(17.5f, 9.5f);
                break;
            case "mute":
                speaker(p);
                p.moveTo(15.5f, 9.5f); p.lineTo(20.5f, 14.5f);
                p.moveTo(20.5f, 9.5f); p.lineTo(15.5f, 14.5f);
                break;
            case "mic":
                p.addRoundRect(new RectF(9, 3, 15, 14.5f), 3, 3, Path.Direction.CW);
                p.moveTo(5.5f, 11.5f); p.quadTo(5.5f, 18, 12, 18); p.quadTo(18.5f, 18, 18.5f, 11.5f);
                p.moveTo(12, 18); p.lineTo(12, 21);
                break;
            case "settings":
                gear(p);
                p.addCircle(12, 12, 3, Path.Direction.CW);
                break;
            case "input":
                p.addRoundRect(new RectF(3, 5, 21, 19), 2.5f, 2.5f, Path.Direction.CW);
                p.moveTo(7, 12); p.lineTo(15, 12);
                p.moveTo(12, 8.8f); p.lineTo(15.2f, 12); p.lineTo(12, 15.2f);
                break;
            case "more":
                solids.addCircle(12, 5.5f, 1.7f, Path.Direction.CW);
                solids.addCircle(12, 12, 1.7f, Path.Direction.CW);
                solids.addCircle(12, 18.5f, 1.7f, Path.Direction.CW);
                break;
            case "playpause":
                solids.moveTo(4, 6); solids.lineTo(12.5f, 12); solids.lineTo(4, 18); solids.close();
                p.moveTo(16, 6.5f); p.lineTo(16, 17.5f);
                p.moveTo(20, 6.5f); p.lineTo(20, 17.5f);
                break;
            case "rewind":
                solids.moveTo(12, 6); solids.lineTo(3.5f, 12); solids.lineTo(12, 18); solids.close();
                solids.moveTo(20.5f, 6); solids.lineTo(12, 12); solids.lineTo(20.5f, 18); solids.close();
                break;
            case "forward":
                solids.moveTo(3.5f, 6); solids.lineTo(12, 12); solids.lineTo(3.5f, 18); solids.close();
                solids.moveTo(12, 6); solids.lineTo(20.5f, 12); solids.lineTo(12, 18); solids.close();
                break;
            case "plus":
                p.moveTo(12, 5.5f); p.lineTo(12, 18.5f);
                p.moveTo(5.5f, 12); p.lineTo(18.5f, 12);
                break;
            case "minus":
                p.moveTo(5.5f, 12); p.lineTo(18.5f, 12);
                break;
            case "remote":
                p.addRoundRect(new RectF(7, 2.5f, 17, 21.5f), 3.5f, 3.5f, Path.Direction.CW);
                p.addCircle(12, 8.5f, 2.6f, Path.Direction.CW);
                solids.addCircle(12, 14.7f, 1.1f, Path.Direction.CW);
                solids.addCircle(12, 18.2f, 1.1f, Path.Direction.CW);
                break;
            case "mouse":
                p.addRoundRect(new RectF(6.5f, 2.5f, 17.5f, 21.5f), 5.5f, 5.5f, Path.Direction.CW);
                p.moveTo(12, 6.5f); p.lineTo(12, 10);
                break;
            case "keyboard":
                p.addRoundRect(new RectF(2.5f, 6, 21.5f, 18.5f), 2.5f, 2.5f, Path.Direction.CW);
                p.moveTo(8, 15); p.lineTo(16, 15);
                for (float x = 6.5f; x < 18; x += 3.7f) solids.addCircle(x, 10.5f, 1, Path.Direction.CW);
                break;
            case "shows":
                p.addRoundRect(new RectF(3, 4.5f, 21, 19.5f), 2.5f, 2.5f, Path.Direction.CW);
                solids.moveTo(10, 8.5f); solids.lineTo(16, 12); solids.lineTo(10, 15.5f); solids.close();
                break;
            case "jarvis":
                solids.moveTo(12, 3); solids.quadTo(12.8f, 10.2f, 20, 12); solids.quadTo(12.8f, 13.8f, 12, 21); solids.quadTo(11.2f, 13.8f, 4, 12); solids.quadTo(11.2f, 10.2f, 12, 3); solids.close();
                break;
            case "send":
                p.moveTo(4, 12); p.lineTo(20, 12);
                p.moveTo(13.5f, 5.5f); p.lineTo(20, 12); p.lineTo(13.5f, 18.5f);
                break;
            case "search":
                p.addCircle(10.5f, 10.5f, 6.5f, Path.Direction.CW);
                p.moveTo(15.3f, 15.3f); p.lineTo(20.5f, 20.5f);
                break;
            case "up":
                p.moveTo(6, 15); p.lineTo(12, 9); p.lineTo(18, 15);
                break;
            case "down":
                p.moveTo(6, 9); p.lineTo(12, 15); p.lineTo(18, 9);
                break;
            default:
                throw new IllegalArgumentException(name);
        }
    }

    /** A cog's outline: eight teeth, each a flat-topped bump on the wheel. */
    private void gear(Path p) {
        int teeth = 8;
        float root = 7.2f, tip = 9.8f;
        double step = 2 * Math.PI / teeth;
        for (int i = 0; i < teeth; i++) {
            double a = i * step;
            double[] angles = {a - step * 0.30, a - step * 0.17, a + step * 0.17, a + step * 0.30};
            float[] radii = {root, tip, tip, root};
            for (int j = 0; j < 4; j++) {
                float x = 12 + radii[j] * (float) Math.cos(angles[j]), y = 12 + radii[j] * (float) Math.sin(angles[j]);
                if (i == 0 && j == 0) p.moveTo(x, y);
                else p.lineTo(x, y);
            }
        }
        p.close();
    }

    private void speaker(Path p) {
        p.moveTo(3.5f, 9.5f); p.lineTo(7, 9.5f); p.lineTo(12, 5); p.lineTo(12, 19); p.lineTo(7, 14.5f); p.lineTo(3.5f, 14.5f); p.close();
    }
}
