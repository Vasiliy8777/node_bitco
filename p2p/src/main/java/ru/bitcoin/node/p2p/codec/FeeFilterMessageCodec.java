package ru.bitcoin.node.p2p.codec;

/**
 * BIP133 feefilter payload codec.
 *
 * The wire value is a signed little-endian int64 CAmount in satoshis per 1000 bytes.
 * Policy decides whether the decoded value is in MoneyRange.
 */
public final class FeeFilterMessageCodec {

    private static final int PAYLOAD_LENGTH = 8;

    private FeeFilterMessageCodec() {
    }

    public static byte[] encode(long feeRate) {
        byte[] payload = new byte[PAYLOAD_LENGTH];
        for (int i = 0; i < PAYLOAD_LENGTH; i++) {
            payload[i] = (byte) (feeRate >>> (8 * i));
        }
        return payload;
    }

    public static long decode(byte[] payload) {
        if (payload == null || payload.length != PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("feefilter payload must be exactly 8 bytes");
        }

        long value = 0L;
        for (int i = 0; i < PAYLOAD_LENGTH; i++) {
            value |= (payload[i] & 0xffL) << (8 * i);
        }
        return value;
    }
}
