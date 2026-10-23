package ru.bitcoin.node.rvn.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class RvnCompactSizeTest {
    @Test
    void roundTripBoundaries() throws Exception {
        for (long v : new long[]{0, 1, 252, 253, 65535, 65536, 4294967295L, 4294967296L, Long.MAX_VALUE}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            RvnCompactSize.write(out, v);
            assertEquals(v, RvnCompactSize.read(new ByteArrayInputStream(out.toByteArray()), Long.MAX_VALUE));
        }
    }

    @Test
    void rejectsNonCanonicalAndOverflow() {
        assertThrows(IOException.class, () -> RvnCompactSize.read(new ByteArrayInputStream(new byte[]{(byte) 253, 1, 0}), 1000));
        assertThrows(IOException.class, () -> RvnCompactSize.read(new ByteArrayInputStream(new byte[]{(byte) 254, (byte) 253, 0, 0, 0}), 1000));
        assertThrows(IOException.class, () -> RvnCompactSize.read(new ByteArrayInputStream(new byte[]{(byte) 255, 0, 0, 0, 0, 0, 0, 0, (byte) 128}), Long.MAX_VALUE));
        assertThrows(IOException.class, () -> RvnCompactSize.read(new ByteArrayInputStream(new byte[]{(byte) 253, (byte) 253, 0}), 252));
    }

    @Test
    void rejectsTruncatedAndNegative() {
        assertThrows(IOException.class, () -> RvnCompactSize.read(new ByteArrayInputStream(new byte[]{(byte) 253, 1}), 1000));
        assertThrows(IllegalArgumentException.class, () -> RvnCompactSize.write(new ByteArrayOutputStream(), -1));
    }
}
