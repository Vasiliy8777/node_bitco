package ru.bitcoin.node.storage.rocksdb;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RocksDbNamespacesTest {
    @Test
    void canonicalNamespaceBytesAreUnique() throws IllegalAccessException {
        var seen = new HashSet<Integer>();
        int count = 0;
        for (Field field : RocksDbNamespaces.class.getDeclaredFields()) {
            if (field.getType() != byte.class || !Modifier.isStatic(field.getModifiers())) continue;
            int value = Byte.toUnsignedInt(field.getByte(null));
            assertTrue(seen.add(value), () -> "Duplicate RocksDB namespace 0x" + Integer.toHexString(value));
            count++;
        }
        assertEquals(count, seen.size());
    }
}
