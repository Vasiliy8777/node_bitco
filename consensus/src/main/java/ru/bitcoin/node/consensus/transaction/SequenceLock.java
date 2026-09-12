package ru.bitcoin.node.consensus.transaction;

public record SequenceLock(
        long minimumHeight,
        long minimumTime
) {

    public static final SequenceLock NONE =
            new SequenceLock(
                    -1L,
                    -1L
            );
}