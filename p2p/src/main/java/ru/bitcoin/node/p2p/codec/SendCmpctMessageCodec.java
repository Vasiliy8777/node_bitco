package ru.bitcoin.node.p2p.codec;

import ru.bitcoin.node.p2p.message.SendCmpctMessage;

public final class SendCmpctMessageCodec {
    private SendCmpctMessageCodec() {
    }

    public static byte[] encode(SendCmpctMessage m) {
        byte[] b = new byte[9];
        b[0] = (byte) (m.highBandwidth() ? 1 : 0);
        write64(b, 1, m.version());
        return b;
    }

    public static SendCmpctMessage decode(byte[] b) {
        if (b == null || b.length != 9) throw new IllegalArgumentException("sendcmpct payload must be 9 bytes");
        if (b[0] != 0 && b[0] != 1) throw new IllegalArgumentException("sendcmpct announce flag must be 0 or 1");
        return new SendCmpctMessage(b[0] == 1, read64(b, 1));
    }

    static void write64(byte[] b, int o, long v) {
        for (int i = 0; i < 8; i++) b[o + i] = (byte) (v >>> (8 * i));
    }

    static long read64(byte[] b, int o) {
        long v = 0;
        for (int i = 0; i < 8; i++) v |= (b[o + i] & 255L) << (8 * i);
        return v;
    }
}
