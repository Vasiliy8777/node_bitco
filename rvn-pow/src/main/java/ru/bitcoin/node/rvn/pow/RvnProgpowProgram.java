package ru.bitcoin.node.rvn.pow;

import java.util.List;

/**
 * A deterministic, bounded executable register program for testing the math
 * and lane-state building blocks. NOT a KawPoW consensus program generator.
 */
public final class RvnProgpowProgram {
    private final List<RvnProgpowOperation> operations;

    public RvnProgpowProgram(List<RvnProgpowOperation> operations) {
        if (operations == null || operations.isEmpty() || operations.size() > 1024 || operations.contains(null))
            throw new IllegalArgumentException("1..1024 non-null operations required");
        this.operations = List.copyOf(operations);
    }

    public int size() {
        return operations.size();
    }

    public void execute(RvnProgpowLaneState state, int lane, int[] externalValues) {
        if (state == null || externalValues == null || externalValues.length != operations.size())
            throw new IllegalArgumentException("external value count");
        for (int i = 0; i < operations.size(); i++) operations.get(i).execute(state, lane, externalValues[i]);
    }
}
