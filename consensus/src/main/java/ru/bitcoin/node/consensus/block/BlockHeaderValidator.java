package ru.bitcoin.node.consensus.block;

import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParameters;

public final class BlockHeaderValidator {

    private BlockHeaderValidator() {
    }

    public static void validate(
            BlockHeader header,
            UInt32 expectedBits,
            long medianTimePast,
            NetworkParameters parameters
    ) {
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