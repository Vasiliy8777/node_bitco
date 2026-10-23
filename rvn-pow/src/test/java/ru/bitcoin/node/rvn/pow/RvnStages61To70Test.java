package ru.bitcoin.node.rvn.pow;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RvnStages61To70Test {
    @Test
    void rv2TigerAndRoundCount() throws Exception {
        RvnX16RPrimitive[] primitives = new RvnX16RPrimitive[16];
        int[] count = {0}, tiger = {0};
        for (int i = 0; i < 16; i++) {
            final int id = i;
            primitives[i] = input -> {
                count[0]++;
                byte[] next = new byte[64];
                next[0] = (byte) id;
                return next;
            };
        }
        byte[] header = new byte[80];
        // 16 rounds, all KECCAK (algorithm id 4).
        for (int i = 4; i < 12; i++) header[i] = 0x44;
        var chain = new RvnX16Rv2Chain(primitives, input -> {
            tiger[0]++;
            return new byte[24];
        });
        assertEquals(4, chain.digest(header)[0]);
        assertEquals(16, count[0]);
        assertEquals(16, tiger[0]);
    }

    @Test
    void rv2RejectsIncorrectTiger() {
        RvnX16RPrimitive[] p = new RvnX16RPrimitive[16];
        for (int i = 0; i < 16; i++) p[i] = in -> new byte[64];
        byte[] header = new byte[80];
        for (int i = 4; i < 12; i++) header[i] = 0x44;
        var chain = new RvnX16Rv2Chain(p, in -> new byte[32]);
        assertThrows(RvnPowException.class, () -> chain.digest(header));
    }

    @Test
    void kawpowNeverAcceptsGenericEngine() {
        var generic = new RvnPowEngine() {
            public RvnPowResult calculate(RvnPowAlgorithm a, byte[] raw) {
                return new RvnPowResult(new byte[32], new byte[32]);
            }
        };
        var router = new RvnPowRouter(new RvnPowSchedule(100, 200), generic);
        byte[] header = new byte[120];
        header[76] = (byte) 200;
        assertThrows(RvnPowException.class, () -> router.calculate(200, header));
    }

    @Test
    void kawpowRejectsFakeMixEvenWithGoodDigest() {
        var kawpow = new RvnKawpowMixEngine() {
            public RvnPowResult calculate(RvnPowAlgorithm a, byte[] raw) {
                return new RvnPowResult(new byte[32], new byte[32]);
            }

            public RvnKawpowMixResult calculateKawpow(byte[] raw) {
                byte[] mix = new byte[32];
                mix[0] = 1;
                return new RvnKawpowMixResult(mix, new RvnPowResult(new byte[32], new byte[32]));
            }
        };
        var router = new RvnPowRouter(new RvnPowSchedule(100, 200), kawpow);
        byte[] header = new byte[120];
        header[76] = (byte) 200;
        assertThrows(RvnPowException.class, () -> router.calculate(200, header));
    }

    @Test
    void vectorsParseAndRejectDuplicates() throws Exception {
        Path f = Files.createTempFile("rvn-vectors", ".tsv");
        try {
            String line = "a\tX16R\t" + RvnPowHex.encode(new byte[80]) + "\t" +
                    RvnPowHex.encode(new byte[32]) + "\t" + RvnPowHex.encode(new byte[32]);
            Files.writeString(f, line + "\n");
            assertEquals(1, RvnPowVectorFile.read(f).size());
            Files.writeString(f, line + "\n" + line + "\n");
            assertThrows(java.io.IOException.class, () -> RvnPowVectorFile.read(f));
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void hexStrictness() {
        assertArrayEquals(new byte[]{(byte) 0xaf}, RvnPowHex.parse("aF"));
        assertThrows(IllegalArgumentException.class, () -> RvnPowHex.parse("xyz"));
        assertThrows(IllegalArgumentException.class, () -> RvnPowHex.parse("0"));
    }
}
