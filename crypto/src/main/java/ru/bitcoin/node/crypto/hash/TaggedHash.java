package ru.bitcoin.node.crypto.hash;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class TaggedHash {
    private TaggedHash() { }
    public static byte[] hash(String tag, byte[]... parts) {
        byte[] prefix = Sha256.hash(tag.getBytes(StandardCharsets.US_ASCII));
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(prefix);
        bytes.writeBytes(prefix);
        for (byte[] part : parts) bytes.writeBytes(part);
        return Sha256.hash(bytes.toByteArray());
    }
}
