package ru.bitcoin.node.consensus.block;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.network.BitcoinNetwork;
import ru.bitcoin.node.protocol.network.NetworkParameters;

public final class Bip30 {

    private static final long EXCEPTION_HEIGHT_1 =
            91_842L;

    private static final Hash256 EXCEPTION_HASH_1 =
            Hash256.fromDisplayHex(
                    "00000000000a4d0a398161ffc163c503"
                            + "763b1f4360639393e0e4c8e300e0caec"
            );

    private static final long EXCEPTION_HEIGHT_2 =
            91_880L;

    private static final Hash256 EXCEPTION_HASH_2 =
            Hash256.fromDisplayHex(
                    "00000000000743f190a18c5577a3c2d2"
                            + "a1f610ae9601ac046a38084ccb7cd721"
            );

    private Bip30() {
    }

    public static boolean shouldEnforce(
            long blockHeight,
            Hash256 blockHash,
            NetworkParameters parameters
    ) {
        if (blockHeight < 0) {
            throw new IllegalArgumentException(
                    "blockHeight must not be negative"
            );
        }

        if (blockHash == null) {
            throw new IllegalArgumentException(
                    "blockHash must not be null"
            );
        }

        if (parameters == null) {
            throw new IllegalArgumentException(
                    "parameters must not be null"
            );
        }

        if (parameters.network()
                != BitcoinNetwork.MAINNET) {

            return true;
        }

        boolean firstException =
                blockHeight == EXCEPTION_HEIGHT_1
                        && blockHash.equals(
                        EXCEPTION_HASH_1
                );

        boolean secondException =
                blockHeight == EXCEPTION_HEIGHT_2
                        && blockHash.equals(
                        EXCEPTION_HASH_2
                );

        return !firstException
                && !secondException;
    }
}