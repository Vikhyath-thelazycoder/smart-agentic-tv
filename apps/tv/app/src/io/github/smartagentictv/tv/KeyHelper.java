package io.github.smartagentictv.tv;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Not part of the app's normal running: LinkService starts this over the TV's own ADB, so it runs as the shell user,
 * which Android 9 lets send key presses (a normal app can't send D-pad keys). It only answers this app's user id.
 * One command per line: a key code, "m dx dy" (move the pointer), "c" (click), "s n" (scroll), "t base64" (type text),
 * "k" (is the TV's on-screen keyboard open), "y" (is YouTube's own keyboard showing), "h code" (hold a key), "r" (the remote's Source button), "p" (a small picture of the screen, only when someone asks what's on it), "v" (version). Typed text is never logged.
 * The pointer goes through the board's own mouse device, named in its profile, because only a real device moves Android's on-screen pointer.
 */
public final class KeyHelper {
    static final String SOCKET = "smarttv_keys", VERSION = "8";
    private static final int INJECT_ASYNC = 0;
    private static final short EV_SYN = 0, EV_KEY = 1, EV_REL = 2, REL_X = 0, REL_Y = 1, BTN_LEFT = 0x110, SCAN_SOURCE_NONE = 0;
    private static final int SCREEN_MIDDLE_X = 640, SCREEN_MIDDLE_Y = 360, HOLD_REPEATS = 6, HOLD_GAP_MS = 120;

    private static String mouseName = "", keyboardName = "";
    private static short sourceScan = SCAN_SOURCE_NONE;
    private static Object input;
    private static Method inject;
    private static FileOutputStream mouse;
    private static Object inputMethods;
    private static Method keyboardHeight, screenshot;
    private static final int[][] YOUTUBE_KEYS = {{496, 300}, {600, 300}, {695, 300}, {400, 68}};
    private static final int[][] YOUTUBE_GAPS = {{582, 310}, {678, 310}, {776, 310}, {484, 310}, {400, 30}};

    public static void main(String[] args) throws Exception {
        int allowedUid = Integer.parseInt(args[0]);
        if (args.length > 3) {
            mouseName = args[1];
            keyboardName = args[2];
            sourceScan = Short.parseShort(args[3]);
        }
        Looper.prepareMainLooper();
        Class<?> manager = Class.forName("android.hardware.input.InputManager");
        input = manager.getMethod("getInstance").invoke(null);
        inject = manager.getMethod("injectInputEvent", android.view.InputEvent.class, int.class);
        try (LocalServerSocket server = new LocalServerSocket(SOCKET)) {
            while (true) {
                LocalSocket client = server.accept();
                if (client.getPeerCredentials().getUid() != allowedUid) {
                    client.close();
                    continue;
                }
                Thread t = new Thread(() -> serve(client));
                t.setDaemon(true);
                t.start();
            }
        }
    }

    private static void serve(LocalSocket client) {
        try (LocalSocket c = client) {
            BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.US_ASCII));
            OutputStream out = c.getOutputStream();
            String line;
            while ((line = in.readLine()) != null) {
                out.write((answer(line.trim()) + "\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } catch (Exception ignored) {
            // The app reconnects if it still needs us.
        }
    }

    private static String answer(String line) throws Exception {
        if (line.equals("v")) return VERSION;
        if (line.equals("p")) return picture();
        boolean ok;
        if (line.startsWith("m ")) {
            String[] p = line.split(" ");
            ok = move(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } else if (line.equals("c")) {
            ok = click();
        } else if (line.startsWith("s ")) {
            ok = scroll(Integer.parseInt(line.substring(2)));
        } else if (line.startsWith("t ")) {
            ok = type(line.substring(2));
        } else if (line.equals("r")) {
            ok = pressSource();
        } else if (line.startsWith("h ")) {
            ok = hold(Integer.parseInt(line.substring(2)));
        } else if (line.equals("k")) {
            ok = keyboardOpen();
        } else if (line.equals("y")) {
            ok = youTubeKeyboard();
        } else {
            ok = press(Integer.parseInt(line));
        }
        return ok ? "ok" : "no";
    }

    private static boolean press(int code) throws Exception {
        long now = SystemClock.uptimeMillis();
        return send(KeyEvent.ACTION_DOWN, code, now) && send(KeyEvent.ACTION_UP, code, now);
    }

    /** A long press: the repeats are what apps see as being held down. */
    private static boolean hold(int code) throws Exception {
        long down = SystemClock.uptimeMillis();
        boolean ok = send(KeyEvent.ACTION_DOWN, code, down);
        for (int i = 1; ok && i <= HOLD_REPEATS; i++) {
            Thread.sleep(HOLD_GAP_MS);
            KeyEvent e = new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, code, i, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, i == 1 ? KeyEvent.FLAG_LONG_PRESS : 0, InputDevice.SOURCE_KEYBOARD);
            ok = (Boolean) inject.invoke(input, e, INJECT_ASYNC);
        }
        return ok && send(KeyEvent.ACTION_UP, code, down);
    }

    private static boolean send(int action, int code, long downTime) throws Exception {
        KeyEvent e = new KeyEvent(downTime, SystemClock.uptimeMillis(), action, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        return (Boolean) inject.invoke(input, e, INJECT_ASYNC);
    }

    /** Types through the same virtual keyboard as the keys. Characters it has no key for are skipped, and reported. */
    private static boolean type(String base64) throws Exception {
        String text = new String(Base64.decode(base64, Base64.NO_WRAP), StandardCharsets.UTF_8);
        KeyCharacterMap map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        boolean all = true;
        for (int i = 0; i < text.length(); i++) {
            KeyEvent[] events = map.getEvents(new char[] {text.charAt(i)});
            if (events == null) {
                all = false;
                continue;
            }
            for (KeyEvent e : events) {
                e.setSource(InputDevice.SOURCE_KEYBOARD);
                inject.invoke(input, e, INJECT_ASYNC);
            }
        }
        return all;
    }

    /**
     * YouTube draws its keyboard itself and tells Android nothing, so this looks at the picture: its Space, Clear and
     * Search buttons and the search box are one exact grey on one exact background, measured on this TV's 1280x720.
     */
    private static boolean youTubeKeyboard() {
        try {
            if (screenshot == null) {
                screenshot = Class.forName("android.view.SurfaceControl").getMethod("screenshot", android.graphics.Rect.class, int.class, int.class, int.class);
            }
            android.graphics.Bitmap shot = (android.graphics.Bitmap) screenshot.invoke(null, new android.graphics.Rect(), 1280, 720, 0);
            if (shot == null) return false;
            android.graphics.Bitmap b = shot.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
            shot.recycle();
            if (b == null) return false;
            boolean yes = true;
            for (int[] p : YOUTUBE_KEYS) yes &= near(b.getPixel(p[0], p[1]), 0x27);
            for (int[] p : YOUTUBE_GAPS) yes &= near(b.getPixel(p[0], p[1]), 0x0F);
            b.recycle();
            return yes;
        } catch (Exception e) {
            return false;
        }
    }

    /** "ok base64" of a small JPEG, or "no" when there's nothing to see: protected apps come out empty or all black. */
    private static String picture() {
        try {
            if (screenshot == null) {
                screenshot = Class.forName("android.view.SurfaceControl").getMethod("screenshot", android.graphics.Rect.class, int.class, int.class, int.class);
            }
            android.graphics.Bitmap shot = (android.graphics.Bitmap) screenshot.invoke(null, new android.graphics.Rect(), 854, 480, 0);
            if (shot == null) return "no";
            android.graphics.Bitmap b = shot.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
            shot.recycle();
            if (b == null) return "no";
            boolean dark = true;
            for (int y = 20; dark && y < b.getHeight(); y += 40) {
                for (int x = 20; dark && x < b.getWidth(); x += 40) dark = near(b.getPixel(x, y), 0);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out);
            b.recycle();
            return dark ? "no" : "ok " + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "no";
        }
    }

    private static boolean near(int color, int grey) {
        return Math.abs(((color >> 16) & 255) - grey) < 6 && Math.abs(((color >> 8) & 255) - grey) < 6 && Math.abs((color & 255) - grey) < 6;
    }

    private static boolean keyboardOpen() {
        try {
            if (inputMethods == null) {
                Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "input_method");
                inputMethods = Class.forName("com.android.internal.view.IInputMethodManager$Stub")
                        .getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
                keyboardHeight = inputMethods.getClass().getMethod("getInputMethodWindowVisibleHeight");
            }
            return (Integer) keyboardHeight.invoke(inputMethods) > 0;
        } catch (Exception e) {
            inputMethods = null;
            return false;
        }
    }

    /** Source has no Android key on this board: the firmware only answers the remote's own button, so this presses that. */
    private static synchronized boolean pressSource() {
        if (sourceScan == SCAN_SOURCE_NONE || keyboardName.isEmpty()) return false;
        try (FileOutputStream remote = new FileOutputStream(devicePath(keyboardName))) {
            ByteBuffer b = events(4);
            event(b, EV_KEY, sourceScan, 1);
            event(b, EV_SYN, (short) 0, 0);
            event(b, EV_KEY, sourceScan, 0);
            event(b, EV_SYN, (short) 0, 0);
            remote.write(b.array());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static synchronized boolean move(int dx, int dy) {
        ByteBuffer b = events(3);
        event(b, EV_REL, REL_X, dx);
        event(b, EV_REL, REL_Y, dy);
        event(b, EV_SYN, (short) 0, 0);
        return write(b);
    }

    private static synchronized boolean click() {
        ByteBuffer b = events(4);
        event(b, EV_KEY, BTN_LEFT, 1);
        event(b, EV_SYN, (short) 0, 0);
        event(b, EV_KEY, BTN_LEFT, 0);
        event(b, EV_SYN, (short) 0, 0);
        return write(b);
    }

    /** This board's mouse has no wheel, so scrolling is sent to whatever is in the middle of the screen. */
    private static boolean scroll(int amount) throws Exception {
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties id = new MotionEvent.PointerProperties();
        id.id = 0;
        id.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords at = new MotionEvent.PointerCoords();
        at.x = SCREEN_MIDDLE_X;
        at.y = SCREEN_MIDDLE_Y;
        at.setAxisValue(MotionEvent.AXIS_VSCROLL, amount);
        MotionEvent e = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, new MotionEvent.PointerProperties[] {id},
                new MotionEvent.PointerCoords[] {at}, 0, 0, 1f, 1f, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, InputDevice.SOURCE_MOUSE, 0);
        boolean ok = (Boolean) inject.invoke(input, e, INJECT_ASYNC);
        e.recycle();
        return ok;
    }

    // Linux's input_event on this 32-bit board: seconds, microseconds, type, code, value (16 bytes). The kernel stamps the time.
    private static ByteBuffer events(int count) {
        return ByteBuffer.allocate(16 * count).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void event(ByteBuffer b, short type, short code, int value) {
        b.putInt(0).putInt(0).putShort(type).putShort(code).putInt(value);
    }

    private static boolean write(ByteBuffer b) {
        if (mouseName.isEmpty()) return false;
        try {
            if (mouse == null) mouse = new FileOutputStream(devicePath(mouseName));
            mouse.write(b.array());
            return true;
        } catch (IOException e) {
            mouse = null;
            return false;
        }
    }

    /** Which /dev/input/eventN a device is: the number can change between starts. */
    private static String devicePath(String name) throws IOException {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/bus/input/devices"))) {
            boolean found = false;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("N: ")) found = line.contains("\"" + name + "\"");
                if (found && line.startsWith("H: ")) {
                    for (String part : line.split("[ =]")) {
                        if (part.startsWith("event")) return "/dev/input/" + part;
                    }
                }
            }
        }
        throw new IOException("no such device");
    }
}
