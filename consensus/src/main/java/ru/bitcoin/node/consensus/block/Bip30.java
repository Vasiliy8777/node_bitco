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

    /** Bitcoin Core keeps the BIP30 fast path only below this future safety limit. */
    public static final long BIP34_IMPLIES_BIP30_LIMIT = 1_983_702L;

    private static final Hash256 MAINNET_BIP34_HASH = Hash256.fromDisplayHex(
            "000000000000024b89b42a942fe0d9fea3bb44ab7bd1b19115dd6a759c0808b8");
    private static final Hash256 TESTNET3_BIP34_HASH = Hash256.fromDisplayHex(
            "0000000023b3a96d3484e5abb3755c413e7d41500f8e2a5c3f0dd01299cd8ef8");

    private Bip30() {
    }

    /**
     * Returns the canonical BIP34 activation block hash used by Bitcoin Core for
     * the BIP30 database-read optimization. Networks whose BIP34Hash is null/zero
     * deliberately return null and keep explicit BIP30 checking.
     */
    public static Hash256 knownBip34ActivationHash(NetworkParameters parameters) {
        if (parameters == null) throw new IllegalArgumentException("parameters must not be null");
        return switch (parameters.network()) {
            case MAINNET -> MAINNET_BIP34_HASH;
            case TESTNET -> TESTNET3_BIP34_HASH;
            case TESTNET4, SIGNET, REGTEST -> null;
        };
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

    /**
     * Bitcoin Core-compatible BIP30 decision once the caller has proven that the
     * candidate branch contains the network's known BIP34 activation block.
     */
    public static boolean shouldEnforce(
            long blockHeight,
            Hash256 blockHash,
            NetworkParameters parameters,
            boolean onKnownBip34Chain
    ) {
        boolean base = shouldEnforce(blockHeight, blockHash, parameters);
        if (!base) return false;
        if (!onKnownBip34Chain) return true;
        return blockHeight < parameters.bip34Height()
                || blockHeight >= BIP34_IMPLIES_BIP30_LIMIT;
    }
}