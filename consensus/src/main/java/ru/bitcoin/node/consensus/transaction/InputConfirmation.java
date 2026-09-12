package ru.bitcoin.node.consensus.transaction;

public record InputConfirmation(
        long height,
        long previousMedianTimePast
) {

    public InputConfirmation {

        if (height < 0) {
            throw new IllegalArgumentException(
                    "height must not be negative"
            );
        }

        if (previousMedianTimePast < 0) {
            throw new IllegalArgumentException(
                    "previousMedianTimePast must not be negative"
            );
        }
    }
}