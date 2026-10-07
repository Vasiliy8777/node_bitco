package ru.bitcoin.node.storage.rocksdb;

import java.util.Arrays;
import java.util.LinkedHashMap;

/** Bounded read cache, owned and guarded by the database monitor. No speculative batch values. */
final class BlockMetadataCache {
    static final int CAPACITY = 65_536;
    private final LinkedHashMap<Key, byte[]> entries = new LinkedHashMap<>(256, 0.75f, true);

    private record Key(byte[] bytes, int hash) {
        Key(byte[] bytes) { this(bytes.clone(), Arrays.hashCode(bytes)); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && Arrays.equals(bytes, key.bytes);
        }
    }

    private boolean eligible(byte[] key) {
        if (key.length != 33) return false;
        return switch (Byte.toUnsignedInt(key[0])) {
            case RocksDbNamespaces.UNDO, RocksDbNamespaces.BLOCK, RocksDbNamespaces.BLOCK_FAILURE,
                    RocksDbNamespaces.BLOCK_AVAILABILITY, RocksDbNamespaces.BLOCK_SKIP_INDEX -> true;
            default -> false;
        };
    }

    boolean contains(byte[] key) { return eligible(key) && entries.containsKey(new Key(key)); }
    byte[] get(byte[] key) {
        byte[] value = entries.get(new Key(key));
        return value == null ? null : value.clone();
    }
    void remember(byte[] key, byte[] value) {
        if (!eligible(key) || (value != null && value.length > 64)) return;
        entries.put(new Key(key), value == null ? null : value.clone());
        if (entries.size() > CAPACITY) entries.remove(entries.keySet().iterator().next());
    }
    void invalidate(byte[] key) { if (eligible(key)) entries.remove(new Key(key)); }
    void invalidatePrefix(byte prefix) { entries.keySet().removeIf(key -> key.bytes[0] == prefix); }
    int size() { return entries.size(); }
}
