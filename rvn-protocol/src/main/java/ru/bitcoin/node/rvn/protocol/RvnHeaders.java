package ru.bitcoin.node.rvn.protocol;

import java.io.*;
import java.util.*;

/**
 * Strict headers-message framing. The 0 transaction count is mandatory.
 */
public final class RvnHeaders {
    public static final int MAX_HEADERS = 2000;

    private RvnHeaders() {
    }

    public static List<RvnBlockHeader> decode(byte[] payload, long activationTime) throws IOException {
        if (payload == null) throw new IllegalArgumentException("payload");
        ByteArrayInputStream in = new ByteArrayInputStream(payload);
        int count = (int) RvnCompactSize.read(in, MAX_HEADERS);
        List<RvnBlockHeader> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            RvnBlockHeader header = RvnBlockHeader.read(in, activationTime);
            long txCount = RvnCompactSize.read(in, 0);
            if (txCount != 0) throw new IOException("headers transaction count must be zero");
            result.add(header);
        }
        if (in.available() != 0) throw new IOException("Trailing bytes in headers message");
        return List.copyOf(result);
    }

    public static byte[] encode(List<RvnBlockHeader> headers) throws IOException {
        if (headers == null || headers.size() > MAX_HEADERS) throw new IllegalArgumentException("headers");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RvnCompactSize.write(out, headers.size());
        for (RvnBlockHeader header : headers) {
            out.write(Objects.requireNonNull(header).serialize());
            RvnCompactSize.write(out, 0);
        }
        return out.toByteArray();
    }
}
