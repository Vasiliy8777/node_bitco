package ru.bitcoin.node.protocol.transaction;

import java.util.Arrays;

public final class TxOut {

    private final long value;
    private final byte[] scriptPubKey;

    public TxOut(
            long value,
            byte[] scriptPubKey
    ) {
        /*if (value < 0) {
            throw new IllegalArgumentException(
                    "Transaction output value cannot be negative"
            );
        }*/

        if (scriptPubKey == null) {
            throw new IllegalArgumentException(
                    "scriptPubKey must not be null"
            );
        }

        this.value = value;
        this.scriptPubKey =
                scriptPubKey.clone();
    }

    public long value() {
        return value;
    }

    public byte[] scriptPubKey() {
        return scriptPubKey.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof TxOut txOut)) {
            return false;
        }

        return value == txOut.value
                && Arrays.equals(
                scriptPubKey,
                txOut.scriptPubKey
        );
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(value);
        result = 31 * result
                + Arrays.hashCode(scriptPubKey);
        return result;
    }
}
