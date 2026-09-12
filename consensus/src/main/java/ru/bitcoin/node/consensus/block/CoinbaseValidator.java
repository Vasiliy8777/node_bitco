package ru.bitcoin.node.consensus.block;

import ru.bitcoin.node.consensus.money.BlockSubsidy;
import ru.bitcoin.node.consensus.money.Money;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxOut;

public final class CoinbaseValidator {

    private CoinbaseValidator() {
    }

    public static void validateReward(
            Transaction coinbase,
            long blockHeight,
            long totalFees,
            NetworkParameters parameters
    ) {
        if (coinbase == null) {
            throw new IllegalArgumentException(
                    "coinbase must not be null"
            );
        }

        if (!coinbase.isCoinbase()) {
            throw new IllegalArgumentException(
                    "transaction must be coinbase"
            );
        }

        if (blockHeight < 0) {
            throw new IllegalArgumentException(
                    "blockHeight must not be negative"
            );
        }

        if (totalFees < 0) {
            throw new IllegalArgumentException(
                    "totalFees must not be negative"
            );
        }

        if (parameters == null) {
            throw new IllegalArgumentException(
                    "parameters must not be null"
            );
        }

        long coinbaseValue = 0L;

        for (TxOut output : coinbase.outputs()) {
            try {
                coinbaseValue =
                        Math.addExact(
                                coinbaseValue,
                                output.value()
                        );
            } catch (ArithmeticException e) {
                throw new BlockValidationException(
                        "Coinbase output value overflow"
                );
            }

            if (!Money.isValidAmount(coinbaseValue)) {
                throw new BlockValidationException(
                        "Coinbase output value out of range"
                );
            }
        }

        long subsidy =
                BlockSubsidy.calculate(
                        blockHeight,
                        parameters
                );

        final long maximumReward;

        try {
            maximumReward =
                    Math.addExact(
                            subsidy,
                            totalFees
                    );
        } catch (ArithmeticException e) {
            throw new BlockValidationException(
                    "Coinbase reward overflow"
            );
        }

        if (coinbaseValue > maximumReward) {
            throw new BlockValidationException(
                    "Coinbase pays too much: "
                            + coinbaseValue
                            + " > "
                            + maximumReward
            );
        }
    }
}