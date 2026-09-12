package ru.bitcoin.node.protocol.network;

import ru.bitcoin.node.common.types.Hash256;

import java.math.BigInteger;

public final class NetworkParameters {

    private final long csvHeight;
    private final long bip34Height;
    private final long subsidyHalvingInterval;
    private final BitcoinNetwork network;
    private final long magic;
    private final int defaultPort;

    private final Hash256 genesisBlockHash;

    private final BigInteger powLimit;

    private final long targetSpacingSeconds;
    private final long targetTimespanSeconds;

    private final boolean allowMinDifficultyBlocks;
    private final boolean enforceBip94;
    private final boolean noRetargeting;

    public NetworkParameters(
            BitcoinNetwork network,
            long magic,
            int defaultPort,
            Hash256 genesisBlockHash,
            BigInteger powLimit,
            long targetSpacingSeconds,
            long targetTimespanSeconds,
            long subsidyHalvingInterval,
            long bip34Height,
            long csvHeight,
            boolean allowMinDifficultyBlocks,
            boolean enforceBip94,
            boolean noRetargeting
    ) {

        if (network == null) {
            throw new IllegalArgumentException(
                    "network must not be null"
            );
        }
        if (csvHeight < 0) {
            throw new IllegalArgumentException(
                    "csvHeight must not be negative"
            );
        }
        if (bip34Height < 0) {
            throw new IllegalArgumentException(
                    "bip34Height must not be negative"
            );
        }
        if (subsidyHalvingInterval <= 0) {
            throw new IllegalArgumentException(
                    "subsidyHalvingInterval must be positive"
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
        this.enforceBip94 = enforceBip94;
        this.noRetargeting = noRetargeting;
        this.subsidyHalvingInterval = subsidyHalvingInterval;
        this.bip34Height = bip34Height;
        this.csvHeight = csvHeight;
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

    public boolean enforceBip94() {
        return enforceBip94;
    }

    public boolean noRetargeting() {
        return noRetargeting;
    }

    public long subsidyHalvingInterval() {
        return subsidyHalvingInterval;
    }
    public long bip34Height() {
        return bip34Height;
    }
    public long csvHeight() {
        return csvHeight;
    }
}

