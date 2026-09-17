package ru.bitcoin.node.p2p.message;

import java.util.Arrays;

public final class BitcoinMessage {

    private final String command;
    private final byte[] payload;

    public BitcoinMessage(
            String command,
            byte[] payload
    ) {
        if (command == null) {
            throw new IllegalArgumentException(
                    "command must not be null"
            );
        }

        if (command.isEmpty()) {
            throw new IllegalArgumentException(
                    "command must not be empty"
            );
        }

        if (command.length() > 12) {
            throw new IllegalArgumentException(
                    "command must not exceed 12 bytes"
            );
        }

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            if (c < 0x20 || c > 0x7e) {
                throw new IllegalArgumentException(
                        "command must contain printable ASCII characters only"
                );
            }
        }

        if (payload == null) {
            throw new IllegalArgumentException(
                    "payload must not be null"
            );
        }

        this.command = command;
        this.payload = payload.clone();
    }

    public String command() {
        return command;
    }

    public byte[] payload() {
        return payload.clone();
    }

    public int payloadLength() {
        return payload.length;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof BitcoinMessage that)) {
            return false;
        }

        return command.equals(that.command)
                && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = command.hashCode();
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }
}