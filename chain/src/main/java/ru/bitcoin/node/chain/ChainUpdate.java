package ru.bitcoin.node.chain;

public record ChainUpdate(
        BlockIndex oldTip,
        BlockIndex newTip,
        ReorganizationPlan reorganizationPlan
) {

    public ChainUpdate {
        if (oldTip == null) {
            throw new IllegalArgumentException(
                    "oldTip must not be null"
            );
        }

        if (newTip == null) {
            throw new IllegalArgumentException(
                    "newTip must not be null"
            );
        }

        if (reorganizationPlan == null) {
            throw new IllegalArgumentException(
                    "reorganizationPlan must not be null"
            );
        }
    }
}