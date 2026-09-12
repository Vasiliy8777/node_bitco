package ru.bitcoin.node.consensus.transaction;

public record UtxoEntry(
        long amount,
        byte[] scriptPubKey,
        long height,
        boolean coinbase
) {

    public UtxoEntry {

        if (amount < 0) {
            throw new IllegalArgumentException(
                    "amount must not be negative"
            );
        }

        if (scriptPubKey == null) {
            throw new IllegalArgumentException(
                    "scriptPubKey must not be null"
            );
        }

        if (height < 0) {
            throw new IllegalArgumentException(
                    "height must not be negative"
            );
        }

        scriptPubKey =
                scriptPubKey.clone();
    }

    @Override
    public byte[] scriptPubKey() {
        return scriptPubKey.clone();
    }
}