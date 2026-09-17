package ru.bitcoin.node.p2p.codec;

import ru.bitcoin.node.p2p.message.PingMessage;
import ru.bitcoin.node.p2p.message.PongMessage;

public final class PingPongMessageCodec {

    public static final int PAYLOAD_LENGTH =
            Long.BYTES;

    private PingPongMessageCodec() {
    }

    public static byte[] encodePing(
            PingMessage message
    ) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null"
            );
        }

        return encodeNonce(
                message.nonce()
        );
    }

    public static byte[] encodePong(
            PongMessage message
    ) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null"
            );
        }

        return encodeNonce(
                message.nonce()
        );
    }

    public static PingMessage decodePing(
            byte[] payload
    ) {
        return new PingMessage(
                decodeNonce(payload)
        );
    }

    public static PongMessage decodePong(
            byte[] payload
    ) {
        return new PongMessage(
                decodeNonce(payload)
        );
    }

    private static byte[] encodeNonce(
            long nonce
    ) {
        byte[] payload =
                new byte[PAYLOAD_LENGTH];

        for (int i = 0;
             i < PAYLOAD_LENGTH;
             i++) {

            payload[i] =
                    (byte) (
                            nonce >>> (8 * i)
                    );
        }

        return payload;
    }

    private static long decodeNonce(
            byte[] payload
    ) {
        if (payload == null) {
            throw new IllegalArgumentException(
                    "payload must not be null"
            );
        }

        if (payload.length != PAYLOAD_LENGTH) {
            throw new IllegalArgumentException(
                    "ping/pong payload must contain exactly 8 bytes"
            );
        }

        long nonce = 0;

        for (int i = 0;
             i < PAYLOAD_LENGTH;
             i++) {

            nonce |=
                    ((long) payload[i] & 0xffL)
                            << (8 * i);
        }

        return nonce;
    }
}