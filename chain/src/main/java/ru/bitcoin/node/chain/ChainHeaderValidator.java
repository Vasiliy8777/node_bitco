package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.block.BlockHeaderValidationException;
import ru.bitcoin.node.consensus.block.BlockHeaderValidator;
import ru.bitcoin.node.consensus.pow.CompactTarget;
import ru.bitcoin.node.consensus.pow.NextWorkRequired;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParameters;

public final class ChainHeaderValidator {

    private ChainHeaderValidator() {
    }

    /** Shared difficulty calculation for a candidate extending this parent. */
    public static UInt32 nextBits(BlockIndex parent, BlockIndexLookup lookup,
                                  NetworkParameters parameters, UInt32 timestamp) {
        java.util.Objects.requireNonNull(parent);
        java.util.Objects.requireNonNull(lookup);
        java.util.Objects.requireNonNull(parameters);
        java.util.Objects.requireNonNull(timestamp);
        var candidate = new BlockHeader(4, parent.hash(), parent.header().merkleRoot(),
                timestamp, parent.header().bits(), new UInt32(0));
        return calculateExpectedBits(Math.addExact(parent.height(),1),candidate,parent,lookup,parameters);
    }

    public static void validate(
            BlockHeader header,
            BlockIndex parent,
            BlockIndexLookup lookup,
            NetworkParameters parameters,
            AdjustedTime adjustedTime
    ) {
        if (header == null) {
            throw new IllegalArgumentException(
                    "header must not be null"
            );
        }

        if (adjustedTime == null) {
            throw new IllegalArgumentException(
                    "adjustedTime must not be null"
            );
        }

        if (parent == null) {
            throw new IllegalArgumentException(
                    "parent must not be null"
            );
        }

        if (lookup == null) {
            throw new IllegalArgumentException(
                    "lookup must not be null"
            );
        }

        if (parameters == null) {
            throw new IllegalArgumentException(
                    "parameters must not be null"
            );
        }

        /*
         * Новый header обязан непосредственно
         * продолжать переданный parent.
         */
        if (!header.previousBlockHash()
                .equals(parent.hash())) {

            throw new IllegalArgumentException(
                    "Header previous block hash "
                            + "does not match parent"
            );
        }

        long nextHeight =
                Math.addExact(
                        parent.height(),
                        1L
                );
        validateBlockVersion(
                header,
                nextHeight,
                parameters
        );

        long medianTimePast =
                MedianTimePast.calculate(
                        parent,
                        lookup
                );

        UInt32 expectedBits =
                calculateExpectedBits(
                        nextHeight,
                        header,
                        parent,
                        lookup,
                        parameters
                );

        BlockHeaderValidator.validate(
                header,
                expectedBits,
                medianTimePast,
                adjustedTime.currentTimeSeconds(),
                parameters
        );
    }

    /** Contextual BIP23 proposal check: identical to normal header validation except PoW. */
    public static void validateWithoutProofOfWork(
            BlockHeader header,
            BlockIndex parent,
            BlockIndexLookup lookup,
            NetworkParameters parameters,
            AdjustedTime adjustedTime
    ) {
        if (header == null || parent == null || lookup == null || parameters == null || adjustedTime == null)
            throw new IllegalArgumentException("Proposal header validation arguments must not be null");
        if (!header.previousBlockHash().equals(parent.hash()))
            throw new IllegalArgumentException("Header previous block hash does not match parent");
        long nextHeight = Math.addExact(parent.height(), 1L);
        validateBlockVersion(header, nextHeight, parameters);
        long medianTimePast = MedianTimePast.calculate(parent, lookup);
        UInt32 expectedBits = calculateExpectedBits(nextHeight, header, parent, lookup, parameters);
        BlockHeaderValidator.validateWithoutProofOfWork(
                header, expectedBits, medianTimePast, adjustedTime.currentTimeSeconds(), parameters);
    }

    static UInt32 calculateExpectedBits(
            long nextHeight,
            BlockHeader candidateHeader,
            BlockIndex parent,
            BlockIndexLookup lookup,
            NetworkParameters parameters
    ) {
        if (parameters.noRetargeting()) {
            return parent.header().bits();
        }

        int interval =
                parameters
                        .difficultyAdjustmentInterval();

        /*
         * Difficulty adjustment boundary.
         *
         * Здесь min-difficulty exception
         * не применяется.
         */
        if (nextHeight % interval == 0) {

            long firstHeight =
                    nextHeight - interval;

            BlockIndex firstBlock =
                    ancestorAtHeight(
                            parent,
                            firstHeight,
                            lookup
                    );

            return NextWorkRequired.calculate(
                    nextHeight,
                    parent.header().bits(),
                    firstBlock.header().bits(),
                    firstBlock.header()
                            .timestamp()
                            .value(),
                    parent.header()
                            .timestamp()
                            .value(),
                    parameters
            );
        }

        /*
         * Special testnet min-difficulty rule.
         */
        if (parameters.allowMinDifficultyBlocks()) {

            long candidateTime =
                    candidateHeader
                            .timestamp()
                            .value();

            long previousTime =
                    parent.header()
                            .timestamp()
                            .value();

            long minDifficultyThreshold;

            try {
                minDifficultyThreshold =
                        Math.addExact(
                                previousTime,
                                Math.multiplyExact(
                                        parameters
                                                .targetSpacingSeconds(),
                                        2L
                                )
                        );
            } catch (ArithmeticException e) {
                throw new IllegalStateException(
                        "Timestamp overflow while calculating "
                                + "testnet min-difficulty rule",
                        e
                );
            }

            /*
             * Bitcoin Core uses strictly greater-than:
             *
             * candidateTime >
             * previousTime + 2 * targetSpacing
             */
            if (candidateTime
                    > minDifficultyThreshold) {

                return new UInt32(
                        CompactTarget.encode(
                                parameters.powLimit()
                        )
                );
            }

            /*
             * Candidate is NOT eligible for the
             * special min-difficulty block.
             *
             * Walk backwards past preceding
             * special min-difficulty blocks until:
             *
             * 1. beginning of this retarget period
             * or
             * 2. last normal-difficulty block.
             */
            long powLimitBits =
                    CompactTarget.encode(
                            parameters.powLimit()
                    );

            BlockIndex current =
                    parent;

            while (current.height() > 0
                    && current.height() % interval != 0
                    && current.header()
                    .bits()
                    .value() == powLimitBits) {

                BlockIndex previous =
                        lookup.find(
                                current.previousBlockHash()
                        );

                if (previous == null) {
                    throw new IllegalStateException(
                            "Missing ancestor while searching "
                                    + "for last non-min-difficulty block: "
                                    + current.previousBlockHash()
                                    .toDisplayHex()
                    );
                }

                current =
                        previous;
            }

            return current.header()
                    .bits();
        }

        /*
         * Mainnet-like normal block inside
         * adjustment period.
         */
        return parent.header()
                .bits();
    }
    static void validateBlockVersion(
            BlockHeader header,
            long blockHeight,
            NetworkParameters parameters
    ) {
        if (header == null) {
            throw new IllegalArgumentException(
                    "header must not be null"
            );
        }

        if (blockHeight < 0) {
            throw new IllegalArgumentException(
                    "blockHeight must not be negative"
            );
        }

        if (parameters == null) {
            throw new IllegalArgumentException(
                    "parameters must not be null"
            );
        }

        /*
         * BIP34:
         * после activation height требуется
         * nVersion >= 2.
         */
        if (blockHeight >= parameters.bip34Height()
                && header.version() < 2) {

            throw new BlockHeaderValidationException(
                    "Block version must be >= 2 after BIP34 activation"
            );
        }

        /*
         * BIP66:
         * после activation height требуется
         * nVersion >= 3.
         */
        if (blockHeight >= parameters.bip66Height()
                && header.version() < 3) {

            throw new BlockHeaderValidationException(
                    "Block version must be >= 3 after BIP66 activation"
            );
        }

        /*
         * BIP65:
         * после activation height требуется
         * nVersion >= 4.
         */
        if (blockHeight >= parameters.bip65Height()
                && header.version() < 4) {

            throw new BlockHeaderValidationException(
                    "Block version must be >= 4 after BIP65 activation"
            );
        }
    }
    private static BlockIndex ancestorAtHeight(
            BlockIndex start,
            long targetHeight,
            BlockIndexLookup lookup
    ) {
        if (targetHeight < 0) {
            throw new IllegalArgumentException(
                    "targetHeight must not be negative"
            );
        }

        if (targetHeight > start.height()) {
            throw new IllegalArgumentException(
                    "targetHeight is above start height"
            );
        }

        BlockIndex current =
                start;

        while (current.height()
                > targetHeight) {

            BlockIndex parent =
                    lookup.find(
                            current.previousBlockHash()
                    );

            if (parent == null) {
                throw new IllegalStateException(
                        "Missing ancestor at height "
                                + (current.height() - 1)
                                + " while calculating "
                                + "difficulty"
                );
            }

            current = parent;
        }

        if (current.height()
                != targetHeight) {

            throw new IllegalStateException(
                    "Unable to find ancestor "
                            + "at height "
                            + targetHeight
            );
        }

        return current;
    }
}
