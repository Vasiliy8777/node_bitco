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
        validate(header, expectedBits, medianTimePast, adjustedTimeSeconds, parameters, true, 0);
    }

    /** BIP23 proposal validation uses all contextual header checks except proof of work. */
    public static void validateWithoutProofOfWork(
            BlockHeader header,
            UInt32 expectedBits,
            long medianTimePast,
            long adjustedTimeSeconds,
            NetworkParameters parameters
    ) {
        validate(header, expectedBits, medianTimePast, adjustedTimeSeconds, parameters, false, 0);
    }

    /** Full contextual ordering, including BIP94's lower timestamp bound before future-time rejection. */
    public static void validate(
            BlockHeader header,
            UInt32 expectedBits,
            long medianTimePast,
            long adjustedTimeSeconds,
            NetworkParameters parameters,
            boolean checkProofOfWork,
            long minimumTimestamp
    ) {
        if (medianTimePast < 0 || minimumTimestamp < 0) {
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

        // Core checks PoW and required difficulty before contextual timestamp
        // rejection. Invalid work must not acquire the non-punishable future reason.
        if (checkProofOfWork && !ProofOfWork.isValid(header, parameters)) {
            throw new BlockHeaderValidationException("Invalid proof of work", "high-hash");
        }
        if (header.bits().value() != expectedBits.value()) {
            throw new BlockHeaderValidationException(
                    "Unexpected difficulty bits. Expected: 0x"
                            + Long.toHexString(expectedBits.value())
                            + ", actual: 0x" + Long.toHexString(header.bits().value()), "bad-diffbits");
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
                            + "median time past", "time-too-old"
            );
        }
        if (header.timestamp().value() < minimumTimestamp)
            throw new BlockHeaderValidationException("Block timestamp violates MTP or BIP94 timewarp bound", "time-timewarp-attack");
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
                    "Block timestamp is too far in the future",
                    BlockHeaderValidationException.Reason.TIME_FUTURE
            );
        }

    }
}
