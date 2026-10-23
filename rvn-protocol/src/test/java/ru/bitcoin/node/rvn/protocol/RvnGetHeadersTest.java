package ru.bitcoin.node.rvn.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RvnGetHeadersTest {
    @Test
    void encodesSingleLocatorInWireOrder() throws Exception {
        byte[] hash = new byte[32];
        Arrays.fill(hash, (byte) 0x7a);
        byte[] stop = new byte[32];
        byte[] encoded = RvnGetHeaders.encode(70028, List.of(hash), stop);
        assertEquals(69, encoded.length);
        assertEquals(70028L, RvnWire.readU32(new ByteArrayInputStream(encoded)));
        assertEquals(1, encoded[4]);
        assertArrayEquals(hash, Arrays.copyOfRange(encoded, 5, 37));
        assertArrayEquals(stop, Arrays.copyOfRange(encoded, 37, 69));
    }

    @Test
    void validatesLocatorBounds() {
        assertThrows(IllegalArgumentException.class, () -> RvnGetHeaders.encode(70028, List.of(), new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> RvnGetHeaders.encode(70028, List.of(new byte[31]), new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> RvnGetHeaders.encode(70028, List.of(new byte[32]), new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> RvnGetHeaders.encode(70028, java.util.Collections.nCopies(102, new byte[32]), new byte[32]));
    }
}
