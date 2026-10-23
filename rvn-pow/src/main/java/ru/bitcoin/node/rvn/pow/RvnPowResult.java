package ru.bitcoin.node.rvn.pow;

import java.util.Arrays;
import java.util.Objects;

/**
 * 32-byte block ID and PoW digest in explicitly documented wire order.
 */
public final class RvnPowResult {
    private final byte[] blockIdWire, powDigestWire;

    public RvnPowResult(byte[] blockIdWire, byte[] powDigestWire) {
        if (blockIdWire == null || blockIdWire.length != 32 ||
                powDigestWire == null || powDigestWire.length != 32)
            throw new IllegalArgumentException("32-byte digests required");
        this.blockIdWire = blockIdWire.clone();
        this.powDigestWire = powDigestWire.clone();
    }

    public byte[] blockIdWire() {
        return blockIdWire.clone();
    }

    public byte[] powDigestWire() {
        return powDigestWire.clone();
    }
}
