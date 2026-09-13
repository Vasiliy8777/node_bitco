package ru.bitcoin.node.consensus.time;

@FunctionalInterface
public interface AdjustedTime {

    /**
     * Текущее adjusted network time
     * в Unix seconds.
     */
    long currentTimeSeconds();
}