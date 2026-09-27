package ru.bitcoin.node.p2p.transport;

import ru.bitcoin.node.crypto.transport.Bip324KeyExchange;
import ru.bitcoin.node.crypto.transport.Bip324KeyMaterial;
import ru.bitcoin.node.crypto.transport.Bip324PacketCipher;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * Stateful BIP324 v2 handshake and encrypted application transport.
 */
public final class Bip324TransportSession {
    public static final int ELLSWIFT_LENGTH = 64;
    public static final int MAX_GARBAGE_LENGTH = 4095;
    private static final int TERMINATOR_LENGTH = 16;
    private static final byte[] EMPTY = new byte[0];

    private final Bip324PacketCipher cipher;
    private final byte[] sessionId;
    private byte[] sendAad;
    private byte[] receiveAad;

    private Bip324TransportSession(Bip324KeyMaterial keys, byte[] sentGarbage, byte[] receivedGarbage) {
        this.cipher = new Bip324PacketCipher(keys);
        this.sessionId = keys.sessionId();
        this.sendAad = sentGarbage.clone();
        this.receiveAad = receivedGarbage.clone();
    }

    public static Bip324TransportSession initiate(InputStream in, OutputStream out,
                                                  NetworkParameters network, SecureRandom random) throws IOException {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(random, "random");
        byte[] magic = magic(network.magic());
        Bip324KeyExchange exchange = new Bip324KeyExchange(true, magic, random);
        byte[] garbage = randomGarbage(random);
        out.write(exchange.publicKey());
        out.write(garbage);
        out.flush();

        byte[] remote = readExactly(in, ELLSWIFT_LENGTH);
        var result = exchange.complete(remote);
        out.write(result.keyMaterial().sendGarbageTerminator());
        out.flush();
        byte[] receivedGarbage = readUntilTerminator(in, result.keyMaterial().receiveGarbageTerminator());
        Bip324TransportSession session = new Bip324TransportSession(result.keyMaterial(), garbage, receivedGarbage);
        session.receiveVersion(in);
        session.sendVersion(out);
        return session;
    }

    /**
     * Respond to v2 after the caller has established that the stream is not v1.
     * prefix contains the already-consumed first bytes of the peer ElligatorSwift key.
     */
    public static Bip324TransportSession respond(InputStream in, OutputStream out, NetworkParameters network,
                                                 SecureRandom random, byte[] prefix) throws IOException {
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.length < 1 || prefix.length > ELLSWIFT_LENGTH)
            throw new IllegalArgumentException("Invalid BIP324 prefix length");
        byte[] remote = new byte[ELLSWIFT_LENGTH];
        System.arraycopy(prefix, 0, remote, 0, prefix.length);
        byte[] tail = readExactly(in, ELLSWIFT_LENGTH - prefix.length);
        System.arraycopy(tail, 0, remote, prefix.length, tail.length);

        byte[] magic = magic(network.magic());
        Bip324KeyExchange exchange = new Bip324KeyExchange(false, magic, random);
        var result = exchange.complete(remote);
        byte[] garbage = randomGarbage(random);
        out.write(exchange.publicKey());
        out.write(garbage);
        out.write(result.keyMaterial().sendGarbageTerminator());
        out.flush();

        Bip324TransportSession session = new Bip324TransportSession(result.keyMaterial(), garbage, EMPTY);
        session.sendVersion(out);
        byte[] receivedGarbage = readUntilTerminator(in, result.keyMaterial().receiveGarbageTerminator());
        session.receiveAad = receivedGarbage;
        session.receiveVersion(in);
        return session;
    }

    public synchronized void send(OutputStream out, BitcoinMessage message) throws IOException {
        byte[] contents = Bip324ApplicationCodec.encode(Objects.requireNonNull(message, "message"));
        writePacket(out, contents, false);
    }

    public synchronized BitcoinMessage receive(InputStream in) throws IOException {
        while (true) {
            Bip324PacketCipher.DecodedPacket packet = readPacket(in);
            if (!packet.ignore()) return Bip324ApplicationCodec.decode(packet.contents());
        }
    }

    public byte[] sessionId() {
        return sessionId.clone();
    }

    private void sendVersion(OutputStream out) throws IOException {
        writePacket(out, EMPTY, false);
    }

    private void receiveVersion(InputStream in) throws IOException {
        while (true) {
            Bip324PacketCipher.DecodedPacket packet = readPacket(in);
            if (!packet.ignore()) return; // v1.0.2 ignores transport-version contents for forward negotiation.
        }
    }

    private void writePacket(OutputStream out, byte[] contents, boolean ignore) throws IOException {
        byte[] packet = cipher.encrypt(contents, sendAad, ignore);
        sendAad = EMPTY;
        out.write(packet);
        out.flush();
    }

    private Bip324PacketCipher.DecodedPacket readPacket(InputStream in) throws IOException {
        byte[] encryptedLength = readExactly(in, Bip324PacketCipher.LENGTH_FIELD_LENGTH);
        int length = cipher.decryptLength(encryptedLength);
        byte[] encryptedPayload = readExactly(in, Bip324PacketCipher.HEADER_LENGTH + length + Bip324PacketCipher.TAG_LENGTH);
        try {
            var decoded = cipher.decryptPayload(encryptedPayload, receiveAad);
            receiveAad = EMPTY;
            return decoded;
        } catch (SecurityException failure) {
            throw new IOException("BIP324 packet authentication failed", failure);
        }
    }

    public static byte[] v1Prefix(NetworkParameters network) {
        byte[] prefix = new byte[16];
        byte[] magic = magic(network.magic());
        System.arraycopy(magic, 0, prefix, 0, 4);
        byte[] version = new byte[]{'v', 'e', 'r', 's', 'i', 'o', 'n', 0, 0, 0, 0, 0};
        System.arraycopy(version, 0, prefix, 4, 12);
        return prefix;
    }

    private static byte[] randomGarbage(SecureRandom random) {
        // Randomized shapability while keeping bounded memory and wire work.
        int length = random.nextInt(MAX_GARBAGE_LENGTH + 1);
        byte[] garbage = new byte[length];
        random.nextBytes(garbage);
        return garbage;
    }

    private static byte[] readUntilTerminator(InputStream in, byte[] terminator) throws IOException {
        byte[] window = new byte[TERMINATOR_LENGTH];
        int filled = 0;
        byte[] garbage = new byte[MAX_GARBAGE_LENGTH];
        int garbageLength = 0;
        while (true) {
            int value = in.read();
            if (value < 0) throw new EOFException("EOF during BIP324 garbage negotiation");
            if (filled < TERMINATOR_LENGTH) window[filled++] = (byte) value;
            else {
                if (garbageLength >= MAX_GARBAGE_LENGTH)
                    throw new IOException("BIP324 garbage terminator not found within 4095 bytes");
                garbage[garbageLength++] = window[0];
                System.arraycopy(window, 1, window, 0, TERMINATOR_LENGTH - 1);
                window[TERMINATOR_LENGTH - 1] = (byte) value;
            }
            if (filled == TERMINATOR_LENGTH && Arrays.equals(window, terminator))
                return Arrays.copyOf(garbage, garbageLength);
        }
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] out = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(out, offset, length - offset);
            if (n < 0) throw new EOFException("Unexpected EOF during BIP324 handshake");
            offset += n;
        }
        return out;
    }

    private static byte[] magic(long value) {
        return new byte[]{(byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)};
    }
}
