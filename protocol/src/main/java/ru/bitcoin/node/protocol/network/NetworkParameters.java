package ru.bitcoin.node.protocol.network;

import ru.bitcoin.node.common.types.Hash256;

import java.math.BigInteger;

public final class NetworkParameters {

    private final BitcoinNetwork network;
    private final long magic;
    private final int defaultPort;

    private final Hash256 genesisBlockHash;

    private final BigInteger powLimit;

    private final long targetSpacingSeconds;
    private final long targetTimespanSeconds;

    private final boolean allowMinDifficultyBlocks;
    private final boolean noRetargeting;

    public NetworkParameters(
            BitcoinNetwork network,
            long magic,
            int defaultPort,
            Hash256 genesisBlockHash,
            BigInteger powLimit,
            long targetSpacingSeconds,
            long targetTimespanSeconds,
            boolean allowMinDifficultyBlocks,
            boolean noRetargeting
    ) {
        if (network == null) {
            throw new IllegalArgumentException(
                    "network must not be null"
            );
        }

        if (genesisBlockHash == null) {
            throw new IllegalArgumentException(
                    "genesisBlockHash must not be null"
            );
        }

        if (powLimit == null || powLimit.signum() <= 0) {
            throw new IllegalArgumentException(
                    "powLimit must be positive"
            );
        }

        if (defaultPort <= 0 || defaultPort > 65535) {
            throw new IllegalArgumentException(
                    "Invalid default port"
            );
        }

        if (targetSpacingSeconds <= 0) {
            throw new IllegalArgumentException(
                    "targetSpacingSeconds must be positive"
            );
        }

        if (targetTimespanSeconds <= 0) {
            throw new IllegalArgumentException(
                    "targetTimespanSeconds must be positive"
            );
        }

        this.network = network;
        this.magic = magic;
        this.defaultPort = defaultPort;
        this.genesisBlockHash = genesisBlockHash;
        this.powLimit = powLimit;
        this.targetSpacingSeconds = targetSpacingSeconds;
        this.targetTimespanSeconds = targetTimespanSeconds;
        this.allowMinDifficultyBlocks = allowMinDifficultyBlocks;
        this.noRetargeting = noRetargeting;
    }

    public BitcoinNetwork network() {
        return network;
    }

    public long magic() {
        return magic;
    }

    public int defaultPort() {
        return defaultPort;
    }

    public Hash256 genesisBlockHash() {
        return genesisBlockHash;
    }

    public BigInteger powLimit() {
        return powLimit;
    }

    public long targetSpacingSeconds() {
        return targetSpacingSeconds;
    }

    public long targetTimespanSeconds() {
        return targetTimespanSeconds;
    }

    public int difficultyAdjustmentInterval() {
        return Math.toIntExact(
                targetTimespanSeconds / targetSpacingSeconds
        );
    }

    public boolean allowMinDifficultyBlocks() {
        return allowMinDifficultyBlocks;
    }

    public boolean noRetargeting() {
        return noRetargeting;
    }
}

