package ru.bitcoin.node.rvn.storage;

import java.io.*;
import java.util.*;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetChanges;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetRuleKey;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetMetadataRules.Metadata;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.HashData;

/** Internal bounded registry/undo encoding; not a network asset serialization. */
final class RvnAssetStateCodec {
    private static final int UNDO_MAGIC = 0x41535431;
    private static final int RULE_MAGIC = 0x52554c31;
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
        if (changes.entries().isEmpty() && changes.rules().isEmpty()) return; // Legacy native-only encoding.
        out.writeInt(UNDO_MAGIC); out.writeInt(changes.entries().size());
        for (var entry : new TreeMap<>(changes.entries()).entrySet()) {
            out.writeUTF(entry.getKey());
            writeOptional(out, entry.getValue().before());
            writeOptional(out, entry.getValue().after());
        }
        if (!changes.rules().isEmpty()) {
            out.writeInt(RULE_MAGIC); out.writeInt(changes.rules().size());
            var entries = new ArrayList<>(changes.rules().entrySet());
            entries.sort(Comparator.comparing(e -> e.getKey().kind().name() + ":" + e.getKey().addressHash() + ":" + e.getKey().name()));
            for (var entry : entries) {
                out.writeByte(entry.getKey().kind().ordinal()); out.writeUTF(entry.getKey().name()); out.writeUTF(entry.getKey().addressHash());
                writeRuleValue(out, entry.getValue().before()); writeRuleValue(out, entry.getValue().after());
            }
        }
    }

    static RvnAssetChanges readChanges(DataInputStream in) throws IOException {
        if (in.available() == 0) return RvnAssetChanges.EMPTY;
        if (in.readInt() != UNDO_MAGIC) throw new IOException("Unknown RVN asset undo encoding");
        int count = in.readInt();
        if (count < 0 || count > in.available() / 10) throw new IOException("Invalid RVN asset undo count");
        var changes = new HashMap<String, RvnAssetChanges.Update>();
        try {
            for (int i = 0; i < count; i++) {
                String name = in.readUTF();
                var update = new RvnAssetChanges.Update(readOptional(in), readOptional(in));
                if (changes.put(name, update) != null) throw new IOException("Duplicate RVN asset undo entry");
            }
            var rules = new HashMap<RvnAssetRuleKey, RvnAssetChanges.RuleUpdate>();
            if (in.available() != 0) {
                if (in.readInt() != RULE_MAGIC) throw new IOException("Unknown RVN rule undo encoding");
                int size = in.readInt();
                if (size <= 0 || size > in.available() / 10) throw new IOException("Invalid RVN rule undo count");
                for (int i = 0; i < size; i++) {
                    int kind = in.readUnsignedByte();
                    if (kind >= RvnAssetRuleKey.Kind.values().length) throw new IOException("Invalid RVN rule kind");
                    var key = new RvnAssetRuleKey(RvnAssetRuleKey.Kind.values()[kind], in.readUTF(), in.readUTF());
                    var update = new RvnAssetChanges.RuleUpdate(readRuleValue(in), readRuleValue(in));
                    if (rules.put(key, update) != null) throw new IOException("Duplicate RVN rule undo entry");
                }
            }
            if (changes.isEmpty() && rules.isEmpty()) throw new IOException("Empty extended RVN asset undo");
            return new RvnAssetChanges(changes, rules);
        } catch (IllegalArgumentException e) { throw new IOException("Invalid RVN asset undo", e); }
    }

    private static void writeRuleValue(DataOutputStream out, Optional<String> value) throws IOException {
        out.writeBoolean(value.isPresent());
        if (value.isPresent()) out.writeUTF(value.get());
    }
    private static Optional<String> readRuleValue(DataInputStream in) throws IOException {
        int flag = in.readUnsignedByte();
        if (flag > 1) throw new IOException("Invalid RVN rule value flag");
        return flag == 0 ? Optional.empty() : Optional.of(in.readUTF());
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
