package ru.bitcoin.node;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.LittleEndian;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LittleEndianTest {

    @Test
    void shouldEncodeUInt32() {
        byte[] result = LittleEndian.uint32(0x12345678L);

        assertArrayEquals(
                new byte[]{
                        0x78,
                        0x56,
                        0x34,
                        0x12
                },
                result
        );
    }

    @Test
    void shouldDecodeUInt32() {
        byte[] bytes = {
                0x78,
                0x56,
                0x34,
                0x12
        };

        long result = LittleEndian.readUInt32(bytes, 0);

        assertEquals(0x12345678L, result);
    }
}
