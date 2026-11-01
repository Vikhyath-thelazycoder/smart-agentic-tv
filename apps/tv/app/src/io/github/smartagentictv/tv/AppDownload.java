package io.github.smartagentictv.tv;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.Semaphore;

/**
 * A small web page on the TV where another phone on the home Wi-Fi can get the phone app. It serves that one file and
 * nothing else. The phone app holds no keys, and a new phone still needs a pairing code from this TV to control it.
 */
final class AppDownload {
    static final int PORT = 47802;
    private static final int MAX_AT_ONCE = 3, READ_MS = 5000, MAX_LINE = 2048;
    private static final String PAGE = "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>My TV</title>"
            + "<style>body{background:#101214;color:#e6e8eb;font:17px/1.5 sans-serif;margin:0;padding:32px 20px;max-width:520px}h1{font-size:28px;margin:0 0 8px}"
            + "a.b{display:block;background:#9dc3ff;color:#0e1a2b;text-align:center;padding:16px;border-radius:16px;text-decoration:none;font-weight:600;margin:24px 0}li{margin:8px 0}</style></head>"
            + "<body><h1>My TV</h1><p>Use this phone as a remote and voice assistant for this TV.</p><a class=b href='/MyTV.apk' download='MyTV.apk'>Download the app</a>"
            + "<ol><li>Open the downloaded file. If Android asks, allow your browser to install apps.</li>"
            + "<li>On the TV, open Settings, then Phone, then Show a pairing code.</li><li>Open My TV on this phone and type the code.</li></ol>"
            + "<p>This phone must be on the same Wi-Fi as the TV.</p></body></html>";

    private final Context context;
    private final Semaphore room = new Semaphore(MAX_AT_ONCE);
    private volatile boolean stopped;
    private ServerSocket server;

    AppDownload(Context context) { this.context = context; }

    void run() {
        while (!stopped) {
            try (ServerSocket s = new ServerSocket(PORT)) {
                server = s;
                while (!stopped) {
                    Socket client = s.accept();
                    if (!room.tryAcquire()) {
                        client.close();
                        continue;
                    }
                    Thread t = new Thread(() -> {
                        try (Socket c = client) {
                            answer(c);
                        } catch (IOException e) {
                            // They went away part way through.
                        } finally {
                            room.release();
                        }
                    });
                    t.setDaemon(true);
                    t.start();
                }
            } catch (IOException e) {
                if (!stopped) AppLog.w("app download port: " + e.getMessage());
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException stop) {
                    return;
                }
            }
        }
    }

    void stop() {
        stopped = true;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // Closing anyway.
        }
    }

    private void answer(Socket c) throws IOException {
        c.setSoTimeout(READ_MS);
        InputStream in = c.getInputStream();
        String[] request = firstLine(in).split(" ");
        // Read the whole request: closing with some of it unread makes the phone lose the end of the file.
        while (!firstLine(in).isEmpty()) { /* headers */ }
        OutputStream out = c.getOutputStream();
        String path = request.length >= 2 && request[0].equals("GET") ? request[1].split("\\?", 2)[0] : "";
        if (path.equals("/")) {
            send(out, "200 OK", "text/html; charset=utf-8", "", PAGE.getBytes(StandardCharsets.UTF_8));
        } else if (path.equals("/MyTV.apk")) {
            send(out, "200 OK", "application/vnd.android.package-archive", "Content-Disposition: attachment; filename=\"MyTV.apk\"\r\n", apk());
        } else {
            send(out, "404 Not Found", "text/plain", "", "Not found".getBytes(StandardCharsets.US_ASCII));
        }
        c.shutdownOutput();
        byte[] left = new byte[256];
        try {
            while (in.read(left) > 0) { /* wait for the phone to finish */ }
        } catch (IOException slow) {
            // It kept the connection open: the file has already gone.
        }
    }

    private static String firstLine(InputStream in) throws IOException {
        StringBuilder s = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0 && b != '\n' && s.length() < MAX_LINE) s.append((char) b);
        return s.toString().trim();
    }

    private byte[] apk() throws IOException {
        try (InputStream in = context.getAssets().open("phone/MyTV.bin")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16 * 1024];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    private static void send(OutputStream out, String status, String type, String extra, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length + "\r\n" + extra + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    /** The link to give another phone, or null when the TV isn't on a network. */
    static String link() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return "http://" + a.getHostAddress() + ":" + PORT;
                }
            }
        } catch (IOException e) {
            AppLog.w("no network address: " + e.getMessage());
        }
        return null;
    }
}
