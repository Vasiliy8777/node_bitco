package ru.bitcoin.node.rvn.protocol;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * RVN getheaders request. Hashes are the 32-byte wire-order block hashes.
 */
public final class RvnGetHeaders {
    public static final int MAX_LOCATORS = 101;

    private RvnGetHeaders() {
    }

    /**
     * Strict decoding of an untrusted peer's getheaders request.
     */
    public record Request(int protocolVersion, List<byte[]> locators, byte[] stopHash) {
        public Request {
            if (locators == null || locators.isEmpty() || locators.size() > MAX_LOCATORS)
                throw new IllegalArgumentException("locator count");
            List<byte[]> copy = new ArrayList<>(locators.size());
            for (byte[] hash : locators) {
                if (hash == null || hash.length != 32) throw new IllegalArgumentException("locator hash");
                copy.add(hash.clone());
            }
            locators = List.copyOf(copy);
            if (stopHash == null || stopHash.length != 32) throw new IllegalArgumentException("stop hash");
            stopHash = stopHash.clone();
        }

        @Override
        public List<byte[]> locators() {
            List<byte[]> copy = new ArrayList<>(locators.size());
            for (byte[] hash : locators) copy.add(hash.clone());
            return List.copyOf(copy);
        }

        @Override
        public byte[] stopHash() {
            return stopHash.clone();
        }
    }

    public static Request decode(byte[] payload) throws IOException {
        if (payload == null) throw new IllegalArgumentException("payload");
        // 4-byte version, 1-byte count, 32-byte locator, 32-byte stop at minimum.
        if (payload.length < 69 || payload.length > 4 + 9 + MAX_LOCATORS * 32 + 32)
            throw new IOException("getheaders payload length out of bounds");
        InputStream in = new ByteArrayInputStream(payload);
        int version = (int) RvnWire.readU32(in);
        int count = (int) RvnCompactSize.read(in, MAX_LOCATORS);
        if (count == 0) throw new IOException("empty locator list");
        List<byte[]> hashes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) hashes.add(RvnWire.readFully(in, 32));
        byte[] stop = RvnWire.readFully(in, 32);
        if (in.read() != -1) throw new IOException("trailing getheaders data");
        return new Request(version, hashes, stop);
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
