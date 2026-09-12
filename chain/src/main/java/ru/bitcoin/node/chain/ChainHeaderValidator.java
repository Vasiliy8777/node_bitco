package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.block.BlockHeaderValidator;
import ru.bitcoin.node.consensus.pow.CompactTarget;
import ru.bitcoin.node.consensus.pow.NextWorkRequired;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParameters;

public final class ChainHeaderValidator {

    private ChainHeaderValidator() {
    }

    public static void validate(
            BlockHeader header,
            BlockIndex parent,
            BlockIndexLookup lookup,
            NetworkParameters parameters
    ) {
        if (header == null) {
            throw new IllegalArgumentException(
                    "header must not be null"
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
                parameters
        );
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