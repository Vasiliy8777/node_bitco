package ru.bitcoin.node.storage.utxo;

import java.util.Arrays;
import java.util.Objects;

public final class StoredUtxo {

    /*
     * Максимальное количество satoshi,
     * которое вообще может существовать в Bitcoin:
     *
     * 21 000 000 BTC * 100 000 000 satoshi
     */
    public static final long MAX_MONEY =
            2_100_000_000_000_000L;

    private final long amount;
    private final byte[] scriptPubKey;
    private final long height;
    private final boolean coinbase;

    public StoredUtxo(
            long amount,
            byte[] scriptPubKey,
            long height,
            boolean coinbase
    ) {
        if (amount < 0 || amount > MAX_MONEY) {
            throw new IllegalArgumentException(
                    "amount must be between 0 and "
                            + MAX_MONEY
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

        this.amount = amount;

        this.scriptPubKey =
                Arrays.copyOf(
                        scriptPubKey,
                        scriptPubKey.length
                );

        this.height = height;
        this.coinbase = coinbase;
    }

    public long amount() {
        return amount;
    }

    public byte[] scriptPubKey() {
        return Arrays.copyOf(
                scriptPubKey,
                scriptPubKey.length
        );
    }

    public long height() {
        return height;
    }

    public boolean coinbase() {
        return coinbase;
    }

    @Override
    public boolean equals(Object o) {

        if (this == o) {
            return true;
        }

        if (!(o instanceof StoredUtxo that)) {
            return false;
        }

        return amount == that.amount
                && height == that.height
                && coinbase == that.coinbase
                && Arrays.equals(
                scriptPubKey,
                that.scriptPubKey
        );
    }

    @Override
    public int hashCode() {

        int result =
                Objects.hash(
                        amount,
                        height,
                        coinbase
                );

        result =
                31 * result
                        + Arrays.hashCode(
                        scriptPubKey
                );

        return result;
    }
}