package ru.bitcoin.node.rvn.pow;

/**
 * Random-access interface to 256-byte KawPoW DAG items.
 * This interface does not implement the ProgPoW execution loop.
 */
public interface RvnKawpowDagReader {
    long itemCount();

    byte[] item(long index);
}
