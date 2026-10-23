package ru.bitcoin.node.rvn.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Minimal Bitcoin-derived version payload. Protocol negotiation is not consensus validation.
 */
public final class RvnVersion {
    private RvnVersion() {
    }

    public static byte[] outbound(int protocolVersion, int remotePort, int localPort) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RvnWire.writeU32(out, protocolVersion);
        writeU64(out, 0);
        writeU64(out, System.currentTimeMillis() / 1000);
        netAddress(out, remotePort);
        netAddress(out, localPort);
        writeU64(out, new SecureRandom().nextLong());
        byte[] agent = "/java-rvn-probe:0.2/".getBytes(StandardCharsets.US_ASCII);
        out.write(agent.length);
        out.write(agent);
        RvnWire.writeU32(out, 0);
        out.write(0);
        return out.toByteArray();
    }

    private static void netAddress(OutputStream out, int port) throws IOException {
        writeU64(out, 0);
        out.write(new byte[10]);
        out.write(255);
        out.write(255);
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(0);
        out.write((port >>> 8) & 255);
        out.write(port & 255);
    }

    public static void writeU64(OutputStream out, long n) throws IOException {
        for (int i = 0; i < 8; i++) out.write((int) (n >>> (8 * i)) & 255);
    }
}
