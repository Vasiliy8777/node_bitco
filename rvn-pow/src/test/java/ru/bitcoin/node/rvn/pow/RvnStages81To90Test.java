package ru.bitcoin.node.rvn.pow;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class RvnStages81To90Test {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void keccak256OfficialKnownAnswers() {
        assertEquals("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
                HEX.formatHex(RvnKeccak256.digest(new byte[0])));
        assertEquals("4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45",
                HEX.formatHex(RvnKeccak256.digest("abc".getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    void keccak512Regression() {
        assertEquals("18587dc2ea106b9a1563e32b3312421ca164c7f1f07bc922a9c83d77cea3a1e5" +
                        "d0c69910739025372dc14ac9642629379540c17e2a65b19d77aa511a9d00bb96",
                HEX.formatHex(RvnKeccak512.digest("abc".getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    void keccakNotNistSha3() throws Exception {
        byte[] input = "abc".getBytes(StandardCharsets.US_ASCII);
        assertFalse(Arrays.equals(RvnKeccak256.digest(input),
                MessageDigest.getInstance("SHA3-256").digest(input)));
    }

    @Test
    void keccakPaddingBoundaries() {
        for (int n : new int[]{0, 1, 71, 72, 73, 135, 136, 137, 143, 144, 145, 271, 272, 273}) {
            byte[] bytes = new byte[n];
            Arrays.fill(bytes, (byte) 0xa5);
            assertEquals(32, RvnKeccak256.digest(bytes).length);
            assertEquals(64, RvnKeccak512.digest(bytes).length);
            assertArrayEquals(RvnKeccak256.digest(bytes), RvnKeccak256.digest(bytes.clone()));
        }
    }

    @Test
    void fnvUnsignedOverflow() {
        assertEquals(0x01000193, RvnKawpowFnv.PRIME);
        assertEquals((0x811c9dc5 * 0x01000193) ^ 0x12345678,
                RvnKawpowFnv.mix(RvnKawpowFnv.OFFSET_BASIS, 0x12345678));
        assertEquals(RvnKawpowFnv.mix(RvnKawpowFnv.OFFSET_BASIS, 0x12345678),
                RvnKawpowFnv.reduce(new int[]{0x12345678}));
        assertThrows(IllegalArgumentException.class, () -> RvnKawpowFnv.reduce(new int[0]));
    }

    @Test
    void unsignedWordEncodingAndOffsets() {
        int[] words = {0, 1, -1, 0x12345678, 0x80000000};
        byte[] encoded = RvnKawpowWords.encode(words);
        assertArrayEquals(words, RvnKawpowWords.decode(encoded));
        assertEquals("0000000001000000ffffffff7856341200000080", HEX.formatHex(encoded));
        assertThrows(IllegalArgumentException.class, () -> RvnKawpowWords.readLE(encoded, 18));
        assertThrows(IllegalArgumentException.class, () -> RvnKawpowWords.decode(new byte[3]));
    }
}
