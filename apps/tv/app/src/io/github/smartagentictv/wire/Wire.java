package io.github.smartagentictv.wire;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The private link between the phone and the TV, shared by both apps (no Android classes, so it also runs on a computer
 * for tests).
 *
 * Pairing: an ECDH exchange, then each side proves it knows the code shown on the TV. Someone only listening learns
 * nothing. Someone actively in the middle during the two minutes a code is valid could guess it offline from the phone's
 * proof, so pairing relies on the code's short life and on the home network.
 * After pairing: both hold a 256-bit secret. Each connection proves it both ways with fresh nonces, then every message
 * is AES-GCM with its own counter, so messages can't be read, changed or replayed.
 */
public final class Wire {
    private Wire() {}

    public static final int PORT = 47800, DISCOVERY_PORT = 47801;
    public static final String ASK = "SATV?", ANSWER = "SATV!";
    private static final int MAX_FRAME = 64 * 1024, NONCE = 32;
    private static final byte PAIR = 1, SESSION = 2, NO = 0, YES = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The TV's side: what it knows about pairing and paired phones. */
    public interface Authority {
        /** The code on the TV's screen, or null when it isn't pairing. */
        String pairingCode();
        void pairFailed();
        /** Saves the phone and returns its id. */
        String pairSucceeded(String phoneName, byte[] secret);
        /** The phone's secret, or null if it isn't paired. */
        byte[] secretFor(String id);
    }

    public static final class Paired {
        public final String id;
        public final byte[] secret;
        Paired(String id, byte[] secret) { this.id = id; this.secret = secret; }
    }

    // MARK: - The TV's side

    /** Answers a new connection: a session's channel, or null if it was a pairing (which ends the connection). */
    public static Channel accept(Socket s, Authority a) throws IOException {
        DataInputStream in = new DataInputStream(s.getInputStream());
        DataOutputStream out = new DataOutputStream(s.getOutputStream());
        DataInputStream hello = new DataInputStream(new ByteArrayInputStream(readFrame(in)));
        byte type = hello.readByte();
        try {
            if (type == PAIR) {
                acceptPair(hello, in, out, a);
                return null;
            }
            if (type != SESSION) throw new IOException("unknown hello");
            return acceptSession(hello, in, out, a);
        } catch (GeneralSecurityException e) {
            throw new IOException("handshake: " + e.getMessage());
        }
    }

    private static void acceptPair(DataInputStream hello, DataInputStream in, DataOutputStream out, Authority a)
            throws IOException, GeneralSecurityException {
        String name = hello.readUTF();
        byte[] clientPub = readBytes(hello);
        String code = a.pairingCode();
        if (code == null) {
            writeFrame(out, new byte[] {NO});
            return;
        }
        KeyPair mine = ecKeys();
        byte[] serverPub = mine.getPublic().getEncoded();
        writeFrame(out, concat(new byte[] {YES}, serverPub));
        byte[][] keys = pairKeys(mine, clientPub, code, clientPub, serverPub);
        byte[] proof = readFrame(in);
        if (!MessageDigest.isEqual(proof, keys[0])) {
            a.pairFailed();
            writeFrame(out, new byte[] {NO});
            return;
        }
        String id = a.pairSucceeded(name, keys[2]);
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(reply);
        d.writeByte(YES);
        d.writeUTF(id);
        d.write(keys[1]);
        writeFrame(out, reply.toByteArray());
    }

    private static Channel acceptSession(DataInputStream hello, DataInputStream in, DataOutputStream out, Authority a)
            throws IOException, GeneralSecurityException {
        String id = hello.readUTF();
        byte[] clientNonce = readBytes(hello);
        byte[] secret = a.secretFor(id);
        if (secret == null || clientNonce.length != NONCE) throw new IOException("unknown phone");
        byte[] serverNonce = random(NONCE);
        byte[] context = sessionContext(id, clientNonce, serverNonce);
        writeFrame(out, concat(serverNonce, hmac(secret, tag("server", context))));
        if (!MessageDigest.isEqual(readFrame(in), hmac(secret, tag("client", context)))) throw new IOException("bad proof");
        byte[] keys = hkdf(secret, context, "session".getBytes(StandardCharsets.UTF_8), 64);
        return new Channel(in, out, slice(keys, 32, 32), slice(keys, 0, 32));
    }

    // MARK: - The phone's side

    /** Pairs with the code on the TV's screen. Returns null when the code is wrong or the TV isn't pairing. */
    public static Paired pair(Socket s, String code, String phoneName) throws IOException {
        DataInputStream in = new DataInputStream(s.getInputStream());
        DataOutputStream out = new DataOutputStream(s.getOutputStream());
        try {
            KeyPair mine = ecKeys();
            byte[] clientPub = mine.getPublic().getEncoded();
            ByteArrayOutputStream hello = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(hello);
            d.writeByte(PAIR);
            d.writeUTF(phoneName);
            writeBytes(d, clientPub);
            writeFrame(out, hello.toByteArray());

            byte[] first = readFrame(in);
            if (first.length < 2 || first[0] != YES) return null;
            byte[] serverPub = slice(first, 1, first.length - 1);
            byte[][] keys = pairKeys(mine, serverPub, code, clientPub, serverPub);
            writeFrame(out, keys[0]);

            DataInputStream reply = new DataInputStream(new ByteArrayInputStream(readFrame(in)));
            if (reply.readByte() != YES) return null;
            String id = reply.readUTF();
            byte[] proof = new byte[32];
            reply.readFully(proof);
            if (!MessageDigest.isEqual(proof, keys[1])) throw new IOException("the TV's proof is wrong");
            return new Paired(id, keys[2]);
        } catch (GeneralSecurityException e) {
            throw new IOException("pairing: " + e.getMessage());
        }
    }

    /** Opens a session as a paired phone. Throws if either side can't prove it holds the secret. */
    public static Channel connect(Socket s, String id, byte[] secret) throws IOException {
        DataInputStream in = new DataInputStream(s.getInputStream());
        DataOutputStream out = new DataOutputStream(s.getOutputStream());
        try {
            byte[] clientNonce = random(NONCE);
            ByteArrayOutputStream hello = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(hello);
            d.writeByte(SESSION);
            d.writeUTF(id);
            writeBytes(d, clientNonce);
            writeFrame(out, hello.toByteArray());

            byte[] answer = readFrame(in);
            if (answer.length != NONCE + 32) throw new IOException("bad answer");
            byte[] serverNonce = slice(answer, 0, NONCE);
            byte[] context = sessionContext(id, clientNonce, serverNonce);
            if (!MessageDigest.isEqual(slice(answer, NONCE, 32), hmac(secret, tag("server", context))))
                throw new IOException("the TV's proof is wrong");
            writeFrame(out, hmac(secret, tag("client", context)));
            byte[] keys = hkdf(secret, context, "session".getBytes(StandardCharsets.UTF_8), 64);
            return new Channel(in, out, slice(keys, 0, 32), slice(keys, 32, 32));
        } catch (GeneralSecurityException e) {
            throw new IOException("session: " + e.getMessage());
        }
    }

    // MARK: - The encrypted channel

    public static final class Channel {
        private final DataInputStream in;
        private final DataOutputStream out;
        private final SecretKeySpec sendKey, receiveKey;
        private long sent, received;

        Channel(DataInputStream in, DataOutputStream out, byte[] sendKey, byte[] receiveKey) {
            this.in = in;
            this.out = out;
            this.sendKey = new SecretKeySpec(sendKey, "AES");
            this.receiveKey = new SecretKeySpec(receiveKey, "AES");
        }

        public synchronized void send(String message) throws IOException {
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, sendKey, new GCMParameterSpec(128, iv(sent++)));
                writeFrame(out, c.doFinal(message.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException e) {
                throw new IOException("encrypt: " + e.getMessage());
            }
        }

        public String receive() throws IOException {
            byte[] frame = readFrame(in);
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, receiveKey, new GCMParameterSpec(128, iv(received++)));
                return new String(c.doFinal(frame), StandardCharsets.UTF_8);
            } catch (GeneralSecurityException e) {
                throw new IOException("a message failed its check");
            }
        }

        private static byte[] iv(long counter) {
            return ByteBuffer.allocate(12).putInt(0).putLong(counter).array();
        }
    }

    // MARK: - Pieces

    private static byte[][] pairKeys(KeyPair mine, byte[] theirPub, String code, byte[] clientPub, byte[] serverPub)
            throws GeneralSecurityException {
        PublicKey their = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(theirPub));
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(mine.getPrivate());
        agreement.doPhase(their, true);
        byte[] transcript = MessageDigest.getInstance("SHA-256").digest(concat(clientPub, serverPub));
        byte[] all = hkdf(agreement.generateSecret(), code.getBytes(StandardCharsets.UTF_8), transcript, 96);
        return new byte[][] {slice(all, 0, 32), slice(all, 32, 32), slice(all, 64, 32)};
    }

    private static KeyPair ecKeys() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    private static byte[] sessionContext(String id, byte[] clientNonce, byte[] serverNonce) {
        return concat(id.getBytes(StandardCharsets.UTF_8), concat(clientNonce, serverNonce));
    }

    private static byte[] tag(String who, byte[] context) {
        return concat(who.getBytes(StandardCharsets.UTF_8), context);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(data);
    }

    private static byte[] hkdf(byte[] input, byte[] salt, byte[] info, int size) throws GeneralSecurityException {
        byte[] prk = hmac(salt.length == 0 ? new byte[32] : salt, input);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] block = new byte[0];
        for (byte i = 1; out.size() < size; i++) {
            block = hmac(prk, concat(concat(block, info), new byte[] {i}));
            out.write(block, 0, block.length);
        }
        return slice(out.toByteArray(), 0, size);
    }

    private static byte[] random(int size) {
        byte[] b = new byte[size];
        RANDOM.nextBytes(b);
        return b;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] c = new byte[a.length + b.length];
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    private static byte[] slice(byte[] a, int from, int size) {
        byte[] b = new byte[size];
        System.arraycopy(a, from, b, 0, size);
        return b;
    }

    private static void writeBytes(DataOutputStream d, byte[] b) throws IOException {
        d.writeShort(b.length);
        d.write(b);
    }

    private static byte[] readBytes(DataInputStream d) throws IOException {
        byte[] b = new byte[d.readUnsignedShort()];
        d.readFully(b);
        return b;
    }

    private static void writeFrame(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }

    private static byte[] readFrame(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > MAX_FRAME) throw new IOException("frame too large");
        byte[] b = new byte[size];
        in.readFully(b);
        return b;
    }
}
