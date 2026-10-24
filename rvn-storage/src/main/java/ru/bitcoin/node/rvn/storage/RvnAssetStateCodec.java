package ru.bitcoin.node.rvn.storage;

import java.io.*;
import java.util.*;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetChanges;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetMetadataRules.Metadata;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.HashData;

/** Internal bounded registry/undo encoding; not a network asset serialization. */
final class RvnAssetStateCodec {
    private static final int UNDO_MAGIC = 0x41535431;
    private RvnAssetStateCodec() {}

    static byte[] encode(Metadata metadata) throws IOException {
        var bytes = new ByteArrayOutputStream();
        write(new DataOutputStream(bytes), metadata);
        return bytes.toByteArray();
    }

    static Metadata decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > 128) throw new IOException("Invalid RVN asset metadata size");
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        var value = read(in);
        if (in.available() != 0) throw new IOException("Trailing RVN asset metadata bytes");
        return value;
    }

    static void writeChanges(DataOutputStream out, RvnAssetChanges changes) throws IOException {
        if (changes.entries().isEmpty()) return; // Preserve legacy native-only undo encoding.
        out.writeInt(UNDO_MAGIC); out.writeInt(changes.entries().size());
        for (var entry : new TreeMap<>(changes.entries()).entrySet()) {
            out.writeUTF(entry.getKey());
            writeOptional(out, entry.getValue().before());
            writeOptional(out, entry.getValue().after());
        }
    }

    static RvnAssetChanges readChanges(DataInputStream in) throws IOException {
        if (in.available() == 0) return RvnAssetChanges.EMPTY;
        if (in.readInt() != UNDO_MAGIC) throw new IOException("Unknown RVN asset undo encoding");
        int count = in.readInt();
        if (count <= 0 || count > in.available() / 10) throw new IOException("Invalid RVN asset undo count");
        var changes = new HashMap<String, RvnAssetChanges.Update>();
        try {
            for (int i = 0; i < count; i++) {
                String name = in.readUTF();
                var update = new RvnAssetChanges.Update(readOptional(in), readOptional(in));
                if (changes.put(name, update) != null) throw new IOException("Duplicate RVN asset undo entry");
            }
            return new RvnAssetChanges(changes);
        } catch (IllegalArgumentException e) { throw new IOException("Invalid RVN asset undo", e); }
    }

    private static void writeOptional(DataOutputStream out, Optional<Metadata> value) throws IOException {
        out.writeBoolean(value.isPresent());
        if (value.isPresent()) write(out, value.get());
    }

    private static Optional<Metadata> readOptional(DataInputStream in) throws IOException {
        int flag = in.readUnsignedByte();
        if (flag > 1) throw new IOException("Invalid RVN asset optional flag");
        return flag == 0 ? Optional.empty() : Optional.of(read(in));
    }

    private static void write(DataOutputStream out, Metadata value) throws IOException {
        out.writeUTF(value.name()); out.writeLong(value.supply()); out.writeByte(value.units());
        out.writeBoolean(value.reissuable()); out.writeBoolean(value.hash() != null);
        if (value.hash() != null) {
            out.writeByte(value.hash().marker()); out.write(HexFormat.of().parseHex(value.hash().digestHex()));
        }
    }

    private static Metadata read(DataInputStream in) throws IOException {
        try {
            String name = in.readUTF(); long supply = in.readLong(); int units = in.readUnsignedByte();
            int reissuable = in.readUnsignedByte(), hasHash = in.readUnsignedByte();
            if (reissuable > 1 || hasHash > 1) throw new IOException("Invalid RVN asset metadata flags");
            HashData hash = null;
            if (hasHash == 1) {
                int marker = in.readUnsignedByte(); byte[] digest = in.readNBytes(32);
                if (digest.length != 32) throw new EOFException("Truncated RVN asset metadata hash");
                hash = new HashData(marker, HexFormat.of().formatHex(digest));
            }
            return new Metadata(name, supply, units, reissuable == 1, hash);
        } catch (IllegalArgumentException e) { throw new IOException("Malformed RVN asset metadata", e); }
    }
}
