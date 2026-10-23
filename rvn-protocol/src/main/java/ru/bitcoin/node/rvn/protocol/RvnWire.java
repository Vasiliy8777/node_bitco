package ru.bitcoin.node.rvn.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;

public final class RvnWire {
    public static final int MAX_PAYLOAD = 4_000_000;

    private RvnWire() {
    }

    public static void write(OutputStream out, RvnNetwork network, RvnMessage msg) throws IOException {
        byte[] body = msg.payload();
        if (body.length > MAX_PAYLOAD) throw new IOException("oversize payload");
        out.write(network.magic());
        byte[] cmd = new byte[12];
        byte[] ascii = msg.command().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, cmd, 0, ascii.length);
        out.write(cmd);
        writeU32(out, body.length);
        out.write(Arrays.copyOf(doubleSha(body), 4));
        out.write(body);
        out.flush();
    }

    public static RvnMessage read(InputStream in, RvnNetwork network) throws IOException {
        byte[] magic = readFully(in, 4);
        if (!Arrays.equals(magic, network.magic())) throw new IOException("RVN network magic mismatch");
        byte[] cmd = readFully(in, 12);
        int end = 0;
        while (end < 12 && cmd[end] != 0) {
            int c = cmd[end] & 255;
            if (!(c >= 97 && c <= 122) && !(c >= 48 && c <= 57) && c != 95) throw new IOException("Invalid command");
            end++;
        }
        if (end == 0) throw new IOException("Empty command");
        for (int i = end; i < 12; i++) if (cmd[i] != 0) throw new IOException("Nonzero command padding");
        long len = readU32(in);
        if (len > MAX_PAYLOAD) throw new IOException("Oversize P2P payload: " + len);
        byte[] checksum = readFully(in, 4);
        byte[] body = readFully(in, (int) len);
        if (!Arrays.equals(checksum, Arrays.copyOf(doubleSha(body), 4))) throw new IOException("Invalid P2P checksum");
        return new RvnMessage(new String(cmd, 0, end, StandardCharsets.US_ASCII), body);
    }

    public static void writeU32(OutputStream out, long v) throws IOException {
        for (int i = 0; i < 4; i++) out.write((int) (v >>> (8 * i)) & 255);
    }

    public static long readU32(InputStream in) throws IOException {
        long n = 0;
        for (int i = 0; i < 4; i++) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            n |= (long) b << (8 * i);
        }
        return n;
    }

    public static byte[] readFully(InputStream in, int count) throws IOException {
        byte[] b = new byte[count];
        new DataInputStream(in).readFully(b);
        return b;
    }

    public static byte[] doubleSha(byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(md.digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
