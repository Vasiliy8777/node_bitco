package ru.bitcoin.node.rvn.pow;

import org.junit.jupiter.api.Test;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class RvnKawpowCoreVectorTest {
    @Test
    void rawHeaderUsesCoreDisplayOrderAndChecksSuppliedMix() throws Exception {
        var engine = new RvnKawpowLightEngine(1024);
        byte[] raw = new byte[120];
        RvnKawpowWords.writeLE(300, raw, 76);
        RvnKawpowWords.writeLE(0x9abcdef0, raw, 80);
        RvnKawpowWords.writeLE(0x12345678, raw, 84);
        var sha = java.security.MessageDigest.getInstance("SHA-256");
        byte[] headerHash = reverse(sha.digest(sha.digest(java.util.Arrays.copyOf(raw, 80))));
        var expected = engine.compute(300, headerHash, 0x123456789abcdef0L);
        var calculated = engine.calculateKawpow(raw);
        assertArrayEquals(reverse(expected.mixHash()), calculated.mixHashWire());
        assertArrayEquals(reverse(expected.finalHash()), calculated.result().blockIdWire());
        assertArrayEquals(calculated.result().blockIdWire(), calculated.result().powDigestWire());
        assertThrows(RvnPowException.class, () -> engine.calculate(RvnPowAlgorithm.KAWPOW, raw));
        System.arraycopy(calculated.mixHashWire(), 0, raw, 88, 32);
        assertArrayEquals(calculated.result().blockIdWire(),
                engine.calculate(RvnPowAlgorithm.KAWPOW, raw).blockIdWire());
        raw[88] ^= 1;
        assertThrows(RvnPowException.class, () -> engine.calculate(RvnPowAlgorithm.KAWPOW, raw));
        assertThrows(RvnPowException.class, () -> engine.calculate(RvnPowAlgorithm.X16R, new byte[80]));
    }

    private static byte[] reverse(byte[] bytes) {
        for (int i = 0; i < bytes.length / 2; i++) {
            byte v = bytes[i]; bytes[i] = bytes[bytes.length - 1 - i]; bytes[bytes.length - 1 - i] = v;
        }
        return bytes;
    }
    @Test
    void coreEpochZeroVector() {
        // RavenProject/Ravencoin src/crypto/ethash/progpow_test_vectors.hpp
        var actual = new RvnKawpowLightEngine(1024).compute(0, new byte[32], 0);
        assertEquals("6e97b47b134fda0c7888802988e1a373affeb28bcd813b6e9a0fc669c935d03a",
                HexFormat.of().formatHex(actual.mixHash()));
        assertEquals("e601a7257a70dc48fccc97a7330d704d776047623b92883d77111fb36870f3d1",
                HexFormat.of().formatHex(actual.finalHash()));
    }

    @Test
    void coreNonzeroInputsAndLaterEpoch() {
        var engine = new RvnKawpowLightEngine(1024);
        check(engine, 49, "63155f732f2bf556967f906155b510c917e48e99685ead76ea83f4eca03ab12b",
                "0000000007073c07", "d36f7e815ee09e74eceb9c96993a3d681edf2bf0921fc7bb710364042db99777",
                "e7ced124598fd2500a55ad9f9f48e3569327fe50493c77a4ac9799b96efb9463");
        check(engine, 50, "9e7248f20914913a73d80a70174c331b1d34f260535ac3631d770e656b5dd922",
                "00000000076e482e", "d6dc634ae837e2785b347648ea515e25e5d8821ae0b95e1c2a9c2d497e0dcfbd",
                "ab0ad7ef8d8ee317dd12d10310aceed7321d34fb263791c2de5776a6658d177e");
        check(engine, 30000, "d34519f72c97cae8892c277776259db3320820cb5279a299d0ef1e155e5c6454",
                "005db8607994ff30", "de0348b69bf91dfe2c3d3dba6f0132e9048a5284e57b8d9d20adc5f3dc0d3236",
                "c7953d848cda6e304f77b4c6d735645c8e8508a5e74c9e9814ef37b19087cd6c");
    }

    private static void check(RvnKawpowLightEngine engine, long height, String header, String nonce,
                              String mix, String hash) {
        var hex = HexFormat.of();
        var actual = engine.compute(height, hex.parseHex(header), Long.parseUnsignedLong(nonce, 16));
        assertEquals(mix, hex.formatHex(actual.mixHash()), "mix at " + height);
        assertEquals(hash, hex.formatHex(actual.finalHash()), "hash at " + height);
    }

    @Test
    void nativeDatasetSizeDropsLastHalfItem() {
        var cache = RvnKawpowCache.build(new byte[32], 17);
        var dag = new RvnKawpowLightDag(cache, RvnKawpowDatasetSizes.datasetBytes(0), 0);
        assertEquals(4194296L, dag.itemCount());
        assertThrows(IndexOutOfBoundsException.class, () -> dag.item(dag.itemCount()));
    }
}
