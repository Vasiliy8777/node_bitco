package ru.bitcoin.node.storage.utxo;

import ru.bitcoin.node.protocol.transaction.OutPoint;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class UtxoChanges {

    private final List<OutPoint> spentOutputs;
    private final List<CreatedUtxo> createdOutputs;

    public UtxoChanges(
            List<OutPoint> spentOutputs,
            List<CreatedUtxo> createdOutputs
    ) {
        if (spentOutputs == null) {
            throw new IllegalArgumentException(
                    "spentOutputs must not be null"
            );
        }

        if (createdOutputs == null) {
            throw new IllegalArgumentException(
                    "createdOutputs must not be null"
            );
        }

        validateSpentOutputs(
                spentOutputs
        );

        validateCreatedOutputs(
                createdOutputs
        );

        this.spentOutputs =
                List.copyOf(
                        spentOutputs
                );

        this.createdOutputs =
                List.copyOf(
                        createdOutputs
                );
    }

    public List<OutPoint> spentOutputs() {
        return spentOutputs;
    }

    public List<CreatedUtxo> createdOutputs() {
        return createdOutputs;
    }

    private static void validateSpentOutputs(
            List<OutPoint> spentOutputs
    ) {
        Set<OutPoint> seen =
                new HashSet<>();

        for (OutPoint outPoint : spentOutputs) {

            if (outPoint == null) {
                throw new IllegalArgumentException(
                        "spentOutputs must not contain null"
                );
            }

            if (!seen.add(outPoint)) {
                throw new IllegalArgumentException(
                        "Duplicate spent OutPoint: "
                                + outPoint
                );
            }
        }
    }

    private static void validateCreatedOutputs(
            List<CreatedUtxo> createdOutputs
    ) {
        Set<OutPoint> seen =
                new HashSet<>();

        for (CreatedUtxo created
                : createdOutputs) {

            if (created == null) {
                throw new IllegalArgumentException(
                        "createdOutputs must not contain null"
                );
            }

            if (!seen.add(
                    created.outPoint()
            )) {
                throw new IllegalArgumentException(
                        "Duplicate created OutPoint: "
                                + created.outPoint()
                );
            }
        }
    }
}