package io.github.smartagentictv.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.Calendar;

/** One glass style, with a new wallpaper and accent color each day. Drawn, so it needs no network and little memory. */
public final class Daily {
    private Daily() {}

    private static final int W = 960, H = 540;
    /** Top color, bottom color, two soft lights and the accent. */
    private static final int[][] DAYS = {
        {0xFF0B1026, 0xFF050714, 0xFF3A4BD8, 0xFFB04BD6, 0xFF7C8CFF},
        {0xFF071B24, 0xFF030B10, 0xFF0F8A8A, 0xFF2E5FD0, 0xFF3FD8C8},
        {0xFF1E0F12, 0xFF0B0506, 0xFFD8603A, 0xFFB02B6E, 0xFFFF8A5C},
        {0xFF0D1A12, 0xFF050A07, 0xFF2F9E5B, 0xFF0E6E86, 0xFF58D68D},
        {0xFF1A1024, 0xFF08050F, 0xFF8A3FD8, 0xFFD84F9A, 0xFFC792FF},
        {0xFF0A1420, 0xFF04080E, 0xFF2E7BD8, 0xFF1FB0C8, 0xFF5CB8FF},
        {0xFF211608, 0xFF0C0803, 0xFFD89A2E, 0xFFC8502A, 0xFFFFC857},
        {0xFF200C1A, 0xFF0B0409, 0xFFD83F7C, 0xFF6A3FD8, 0xFFFF7EB0},
        {0xFF0C1C1C, 0xFF040B0B, 0xFF1FA89A, 0xFF7FB03A, 0xFF6FE3C4},
        {0xFF12122A, 0xFF06060F, 0xFF5A52E0, 0xFF2E9BD8, 0xFF9A94FF},
    };

    private static Bitmap image;
    private static int imageDay = -1;

    private static int day() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) * 366 + c.get(Calendar.DAY_OF_YEAR);
    }

    private static int[] today() {
        return DAYS[day() % DAYS.length];
    }

    public static int accent() {
        return today()[4];
    }

    public static synchronized Bitmap wallpaper(Context c) {
        int d = day();
        if (image != null && imageDay == d) return image;
        int[] p = today();
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas g = new Canvas(b);
        Paint paint = new Paint(Paint.DITHER_FLAG);
        paint.setDither(true);
        paint.setShader(new LinearGradient(0, 0, 0, H, p[0], p[1], Shader.TileMode.CLAMP));
        g.drawRect(0, 0, W, H, paint);
        // Where the lights sit changes each day too, so two days with close colors still look different.
        float[][] spots = {{0.78f, 0.18f, 0.18f, 0.92f}, {0.2f, 0.12f, 0.85f, 0.85f}, {0.62f, 0.05f, 0.1f, 0.6f},
                {0.9f, 0.55f, 0.35f, 0.05f}, {0.3f, 0.78f, 0.8f, 0.2f}};
        float[] s = spots[d % spots.length];
        light(g, paint, s[0] * W, s[1] * H, H * 0.95f, p[2]);
        light(g, paint, s[2] * W, s[3] * H, H * 0.8f, p[3]);
        image = b;
        imageDay = d;
        return b;
    }

    private static void light(Canvas g, Paint paint, float x, float y, float r, int color) {
        int soft = (color & 0x00FFFFFF) | 0x66000000, clear = color & 0x00FFFFFF;
        paint.setShader(new RadialGradient(x, y, r, new int[] {soft, (color & 0x00FFFFFF) | 0x22000000, clear},
                new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
        g.drawRect(0, 0, W, H, paint);
    }
}
