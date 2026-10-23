package ru.bitcoin.node.rvn.storage;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;
import ru.bitcoin.node.rvn.consensus.RvnHash256;
import ru.bitcoin.node.rvn.protocol.RvnBlockHeader;
import ru.bitcoin.node.rvn.protocol.RvnActivation;

/** Locally validated header metadata. This is not a validated full block or UTXO tip. */
public record RvnStoredHeader(RvnHash256 id, long height, BigInteger chainWork,
                              RvnHash256 skipId, byte[] raw) {
    public RvnStoredHeader {
        Objects.requireNonNull(id); Objects.requireNonNull(skipId); Objects.requireNonNull(chainWork);
        if (height < 0 || chainWork.signum() <= 0 || chainWork.bitLength() > 256)
            throw new IllegalArgumentException("Invalid stored height/work");
        try { RvnBlockHeader.parse(raw, RvnActivation.MAINNET_KAWPOW_TIME); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Invalid stored header", e); }
        raw = raw.clone();
    }
    @Override public byte[] raw() { return raw.clone(); }
    public RvnHash256 parentId() { return new RvnHash256(Arrays.copyOfRange(raw, 4, 36)); }
    public long timestamp() { return u32(68); }
    public long bits() { return u32(72); }
    public int version() { return (int) u32(0); }
    private long u32(int offset) {
        return (raw[offset] & 255L) | ((raw[offset + 1] & 255L) << 8)
                | ((raw[offset + 2] & 255L) << 16) | ((raw[offset + 3] & 255L) << 24);
    }
}
