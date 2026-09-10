package ru.bitcoin.node.protocol.transaction;

public final class TxOut {

    private final long value;
    private final byte[] scriptPubKey;

    public TxOut(
            long value,
            byte[] scriptPubKey
    ) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "Transaction output value cannot be negative"
            );
        }

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
}
