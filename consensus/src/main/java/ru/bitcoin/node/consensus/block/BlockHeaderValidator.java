package ru.bitcoin.node.consensus.block;

import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParameters;

public final class BlockHeaderValidator {
    public static final long MAX_FUTURE_BLOCK_TIME_SECONDS =
            2L * 60L * 60L;
    private BlockHeaderValidator() {
    }

    public static void validate(
            BlockHeader header,
            UInt32 expectedBits,
            long medianTimePast,
            long adjustedTimeSeconds,
            NetworkParameters parameters
    ) {
        if (medianTimePast < 0) {
            throw new IllegalArgumentException(
                    "medianTimePast must not be negative"
            );
        }

        if (adjustedTimeSeconds < 0) {
            throw new IllegalArgumentException(
                    "adjustedTimeSeconds must not be negative"
            );
        }
        if (header == null) {
            throw new IllegalArgumentException(
                    "header must not be null"
            );
        }

        if (expectedBits == null) {
            throw new IllegalArgumentException(
                    "expectedBits must not be null"
            );
        }

        if (parameters == null) {
            throw new IllegalArgumentException(
                    "parameters must not be null"
            );
        }

        /*
         * Bitcoin requires:
         *
         * block timestamp > MedianTimePast
         */
        if (header.timestamp().value()
                <= medianTimePast) {

            throw new BlockHeaderValidationException(
                    "Block timestamp must be greater than "
                            + "median time past"
            );
        }
        long maximumAllowedTimestamp;

        try {
            maximumAllowedTimestamp =
                    Math.addExact(
                            adjustedTimeSeconds,
                            MAX_FUTURE_BLOCK_TIME_SECONDS
                    );
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "adjustedTimeSeconds is too large",
                    e
            );
        }

        /*
         * Bitcoin accepts timestamp exactly at:
         *
         * adjustedTime + 2 hours
         *
         * and rejects only strictly greater values.
         */
        if (header.timestamp().value()
                > maximumAllowedTimestamp) {

            throw new BlockHeaderValidationException(
                    "Block timestamp is too far in the future"
            );
        }

        /*
         * nBits must exactly match the difficulty
         * required by the active chain.
         */
        if (header.bits().value()
                != expectedBits.value()) {

            throw new BlockHeaderValidationException(
                    "Unexpected difficulty bits. "
                            + "Expected: 0x"
                            + Long.toHexString(
                            expectedBits.value()
                    )
                            + ", actual: 0x"
                            + Long.toHexString(
                            header.bits().value()
                    )
            );
        }

        /*
         * ProofOfWork also checks:
         *
         * target > 0
         * target <= network powLimit
         * hash <= target
         */
        if (!ProofOfWork.isValid(
                header,
                parameters
        )) {
            throw new BlockHeaderValidationException(
                    "Invalid proof of work"
            );
        }
    }
}