import io.github.smartagentictv.wire.Wire;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Tests the phone link from a computer, against the real TV. Saved pairing goes in a file you name; keep it private.
 *   find                     search the network for the TV
 *   wrong <ip> <code>        pair with a wrong code (must be refused)
 *   pair <ip> <code> <file>  pair and save
 *   badsecret <ip> <file>    connect with the right id and a wrong secret (must be refused)
 *   send <ip> <file> <message...>  connect and send messages, printing each answer and its time
 *   ask <ip> <file> <words...>     ask Jarvis, which really acts on the TV
 */
public final class LinkTest {
    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "find": find(); break;
            case "wrong":
                try (Socket s = open(a[1])) { System.out.println(Wire.pair(s, a[2], "test") == null ? "refused" : "ACCEPTED"); }
                break;
            case "pair":
                try (Socket s = open(a[1])) {
                    Wire.Paired p = Wire.pair(s, a[2], "mac-test");
                    if (p == null) { System.out.println("refused"); return; }
                    File f = new File(a[3]);
                    Files.write(f.toPath(), (p.id + " " + hex(p.secret)).getBytes(StandardCharsets.US_ASCII));
                    f.setReadable(false, false);
                    f.setReadable(true, true);
                    System.out.println("paired");
                }
                break;
            case "badsecret":
                try (Socket s = open(a[1])) {
                    String id = read(a[2])[0];
                    Wire.Channel c = Wire.connect(s, id, new byte[32]);
                    c.send("ping");
                    System.out.println("ACCEPTED: " + c.receive());
                } catch (java.io.IOException e) {
                    System.out.println("refused: " + e.getMessage());
                }
                break;
            case "send":
                try (Socket s = open(a[1])) {
                    String[] saved = read(a[2]);
                    Wire.Channel c = Wire.connect(s, saved[0], unhex(saved[1]));
                    for (String m : Arrays.copyOfRange(a, 3, a.length)) {
                        long t = System.nanoTime();
                        c.send(m);
                        String r = c.receive();
                        System.out.printf("%s -> %s (%d ms)%n", m, r, (System.nanoTime() - t) / 1_000_000);
                    }
                }
                break;
            case "ask": // ask <ip> <file> <words...>: each one to Jarvis, waiting for the answer as the phone does
                try (Socket s = open(a[1])) {
                    String[] saved = read(a[2]);
                    Wire.Channel c = Wire.connect(s, saved[0], unhex(saved[1]));
                    for (String m : Arrays.copyOfRange(a, 3, a.length)) {
                        long t = System.nanoTime();
                        c.send("jarvis " + m);
                        c.receive();
                        String r = "jarvis wait";
                        while (r.equals("jarvis wait")) {
                            Thread.sleep(300);
                            c.send("jarvisget");
                            r = c.receive();
                        }
                        System.out.printf("%s -> %s (%d ms)%n", m, r.substring(7), (System.nanoTime() - t) / 1_000_000);
                    }
                }
                break;
            default: throw new IllegalArgumentException(a[0]);
        }
    }

    private static void find() throws Exception {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(2000);
            byte[] ask = Wire.ASK.getBytes(StandardCharsets.US_ASCII);
            s.send(new DatagramPacket(ask, ask.length, InetAddress.getByName("255.255.255.255"), Wire.DISCOVERY_PORT));
            byte[] b = new byte[128];
            DatagramPacket p = new DatagramPacket(b, b.length);
            s.receive(p);
            System.out.println(p.getAddress().getHostAddress() + " " + new String(b, 0, p.getLength(), StandardCharsets.UTF_8));
        }
    }

    private static Socket open(String ip) throws Exception {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.setSoTimeout(60_000); // Jarvis can take a while
        s.connect(new InetSocketAddress(ip, Wire.PORT), 4000);
        return s;
    }

    private static String[] read(String file) throws Exception {
        return new String(Files.readAllBytes(new File(file).toPath()), StandardCharsets.US_ASCII).trim().split(" ");
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    private static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}
