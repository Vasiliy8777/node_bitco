package ru.bitcoin.node.rvn.pow;

/**
 * An immutable, validated operation on a single lane's register file.
 */
public record RvnProgpowOperation(Kind kind, int destination, int source, int selector) {
    public enum Kind {MATH, MERGE}

    public RvnProgpowOperation {
        if (kind == null || destination < 0 || destination >= 32 || source < 0 || source >= 32)
            throw new IllegalArgumentException("operation fields");
    }

    public void execute(RvnProgpowLaneState state, int lane, int externalValue) {
        if (kind == Kind.MATH) state.math(lane, destination, source, selector);
        else state.merge(lane, destination, externalValue, selector);
    }
}
