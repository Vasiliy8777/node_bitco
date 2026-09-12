package ru.bitcoin.node.consensus.transaction;

import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.transaction.Transaction;

import java.util.List;

public final class SequenceLockValidator {

    private SequenceLockValidator() {
    }

    public static void validate(
            Transaction transaction,
            List<InputConfirmation> inputs,
            long blockHeight,
            long previousMedianTimePast,
            NetworkParameters networkParameters
    ) {
        if (transaction == null) {
            throw new IllegalArgumentException(
                    "transaction must not be null"
            );
        }

        if (inputs == null) {
            throw new IllegalArgumentException(
                    "inputs must not be null"
            );
        }

        if (blockHeight < 0) {
            throw new IllegalArgumentException(
                    "blockHeight must not be negative"
            );
        }

        if (previousMedianTimePast < 0) {
            throw new IllegalArgumentException(
                    "previousMedianTimePast must not be negative"
            );
        }

        if (networkParameters == null) {
            throw new IllegalArgumentException(
                    "networkParameters must not be null"
            );
        }

        /*
         * До активации CSV/BIP68 sequence locks
         * consensus-правилом не являются.
         */
        if (blockHeight < networkParameters.csvHeight()) {
            return;
        }

        SequenceLock lock =
                SequenceLocks.calculate(
                        transaction,
                        inputs
                );

        if (!SequenceLocks.evaluate(
                lock,
                blockHeight,
                previousMedianTimePast
        )) {
            throw new TransactionValidationException(
                    "Transaction sequence locks are not satisfied"
            );
        }
    }
}