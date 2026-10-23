package ru.bitcoin.node.rvn.pow;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class RvnStages71To80Test {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void keccak512EmptyKnownVector() {
        assertEquals("0eab42de4c3ceb9235fc91acffe746b29c29a8c366b7c60e4e67c466f36a4304" +
                        "c00fa9caf9d87976ba469bcbe06713b435f091ef2769fb160cdab33d3670680e",
                HEX.formatHex(RvnKeccak512.digest(new byte[0])));
    }

    @Test
    void keccak512AbcKnownVector() {
        assertEquals("18587dc2ea106b9a1563e32b3312421ca164c7f1f07bc922a9c83d77cea3a1e5" +
                        "d0c69910739025372dc14ac9642629379540c17e2a65b19d77aa511a9d00bb96",
                HEX.formatHex(RvnKeccak512.digest("abc".getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    void keccakIsNotSha3() throws Exception {
        byte[] input = "abc".getBytes(StandardCharsets.US_ASCII);
        assertFalse(Arrays.equals(RvnKeccak512.digest(input),
                MessageDigest.getInstance("SHA3-512").digest(input)));
    }

    @Test
    void sha512MatchesJdk() throws Exception {
        byte[] input = new byte[131];
        Arrays.fill(input, (byte) 0xa5);
        assertArrayEquals(MessageDigest.getInstance("SHA-512").digest(input),
                new RvnSha512Primitive().digest(input));
    }

    @Test
    void registryIsFailClosed() throws Exception {
        var registry = new RvnX16RPrimitiveRegistry();
        assertEquals(14, registry.missing().size());
        assertThrows(RvnPowException.class, registry::requireComplete);
        for (int i = 0; i < 16; i++)
            if (i != 4 && i != 15) registry.set(i, bytes -> new byte[64]);
        var complete = registry.requireComplete();
        assertEquals(16, complete.length);
        assertArrayEquals(RvnKeccak512.digest(new byte[0]), complete[4].digest(new byte[0]));
        assertArrayEquals(MessageDigest.getInstance("SHA-512").digest(new byte[0]),
                complete[15].digest(new byte[0]));
        complete[4] = null;
        assertNotNull(registry.requireComplete()[4]);
    }

    @Test
    void nullInputsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RvnKeccak512.digest(null));
        assertThrows(RvnPowException.class, () -> new RvnKeccak512Primitive().digest(null));
        assertThrows(RvnPowException.class, () -> new RvnSha512Primitive().digest(null));
    }
}
