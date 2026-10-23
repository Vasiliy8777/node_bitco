package ru.bitcoin.node.rvn.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * RVN getheaders request. Hashes are the 32-byte wire-order block hashes.
 */
public final class RvnGetHeaders {
    public static final int MAX_LOCATORS = 101;

    private RvnGetHeaders() {
    }

    public static byte[] encode(int protocolVersion, List<byte[]> locatorHashes, byte[] stopHash) {
        if (locatorHashes == null || locatorHashes.isEmpty() || locatorHashes.size() > MAX_LOCATORS)
            throw new IllegalArgumentException("Locator count must be 1..101");
        if (stopHash == null || stopHash.length != 32)
            throw new IllegalArgumentException("Stop hash must be 32 bytes");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(4 + 9 + locatorHashes.size() * 32 + 32);
            RvnWire.writeU32(out, Integer.toUnsignedLong(protocolVersion));
            RvnCompactSize.write(out, locatorHashes.size());
            for (byte[] hash : locatorHashes) {
                if (hash == null || hash.length != 32)
                    throw new IllegalArgumentException("Each locator hash must be 32 bytes");
                out.write(hash);
            }
            out.write(stopHash);
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
