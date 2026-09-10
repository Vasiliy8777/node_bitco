package ru.bitcoin.node.consensus.pow;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.math.BigInteger;

public final class ProofOfWork {

    private ProofOfWork() {
    }

    public static boolean isValid(
            BlockHeader header,
            NetworkParameters parameters
    ) {
        if (header == null) {
            throw new IllegalArgumentException(
                    "header must not be null"
            );
        }

        if (parameters == null) {
            throw new IllegalArgumentException(
                    "parameters must not be null"
            );
        }

        BigInteger target =
                CompactTarget.decode(
                        header.bits().value()
                );

        if (target.signum() <= 0) {
            return false;
        }

        /*
         * Блок не имеет права заявить target легче,
         * чем разрешено сетью.
         */
        if (target.compareTo(
                parameters.powLimit()
        ) > 0) {
            return false;
        }

        BigInteger hashValue =
                hashToInteger(
                        header.hash()
                );

        return hashValue.compareTo(target) <= 0;
    }

    public static BigInteger hashToInteger(
            Hash256 hash
    ) {
        if (hash == null) {
            throw new IllegalArgumentException(
                    "hash must not be null"
            );
        }

        byte[] raw =
                hash.bytes();

        byte[] bigEndian =
                new byte[raw.length];

        for (int i = 0; i < raw.length; i++) {
            bigEndian[i] =
                    raw[raw.length - 1 - i];
        }

        return new BigInteger(
                1,
                bigEndian
        );
    }
}