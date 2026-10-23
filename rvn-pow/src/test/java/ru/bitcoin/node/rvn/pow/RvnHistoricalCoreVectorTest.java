package ru.bitcoin.node.rvn.pow;

import fr.cryptohash.Tiger;
import org.junit.jupiter.api.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

class RvnHistoricalCoreVectorTest {
    @Test
    void sharedEngineHasNoCrossThreadDigestState() throws Exception {
        var engine = new RvnJavaPowEngine(new RvnKawpowLightEngine(0));
        byte[] header = RvnHistoricalPowSelfTest.mainnetGenesisHeader();
        byte[] x16r = engine.calculate(RvnPowAlgorithm.X16R, header).blockIdWire();
        byte[] x16rv2 = engine.calculate(RvnPowAlgorithm.X16RV2, header).blockIdWire();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Callable<byte[]>>();
            for (int i = 0; i < 32; i++) {
                final var algorithm = i % 2 == 0 ? RvnPowAlgorithm.X16R : RvnPowAlgorithm.X16RV2;
                jobs.add(() -> engine.calculate(algorithm, header).blockIdWire());
            }
            var results = pool.invokeAll(jobs);
            for (int i = 0; i < results.size(); i++)
                assertArrayEquals(i % 2 == 0 ? x16r : x16rv2, results.get(i).get());
        }
    }
    @Test
    void startupSelfTestRejectsPlaceholderEngine() throws Exception {
        assertThrows(RvnPowException.class, () -> RvnHistoricalPowSelfTest.requireValid(
                (algorithm, raw) -> new RvnPowResult(new byte[32], new byte[32])));
        var real = new RvnJavaPowEngine(new RvnKawpowLightEngine(0));
        RvnHistoricalPowSelfTest.requireValid(real);
        assertThrows(RvnPowException.class, () -> RvnHistoricalPowSelfTest.requireValid(
                (algorithm, raw) -> new RvnPowResult(real.calculate(algorithm, raw).blockIdWire(), new byte[32])));
    }
    @Test
    void allPrimitivesTigerAndBothChainsMatchPinnedCoreC() throws Exception {
        var engine = new RvnJavaPowEngine(new RvnKawpowLightEngine(0));
        var primitives = RvnJavaPowEngine.primitives().requireComplete();
        var hex = HexFormat.of();
        int primitiveCount = 0, tigerCount = 0, chainCount = 0;
        var resource = Objects.requireNonNull(getClass().getResourceAsStream("/core-reference/x16-vectors.tsv"));
        try (var reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.startsWith("#")) continue;
                String[] fields = line.split("\t", -1);
                assertEquals(4, fields.length);
                byte[] input = hex.parseHex(fields[2]);
                byte[] actual;
                switch (fields[0]) {
                    case "P" -> { actual = primitives[Integer.parseInt(fields[1])].digest(input); primitiveCount++; }
                    case "T" -> { actual = new Tiger().digest(input); tigerCount++; }
                    case "X" -> {
                        var result = engine.calculate(RvnPowAlgorithm.valueOf(fields[1]), input);
                        actual = result.blockIdWire();
                        assertArrayEquals(actual, result.powDigestWire());
                        chainCount++;
                    }
                    default -> throw new AssertionError("Unknown fixture kind");
                }
                assertEquals(fields[3], hex.formatHex(actual), fields[0] + ":" + fields[1] + " input=" + fields[2]);
            }
        }
        assertEquals(80, primitiveCount);
        assertEquals(5, tigerCount);
        assertEquals(40, chainCount);
    }

    @Test
    void independentlyPublishedMainnetGenesis() throws Exception {
        byte[] header = new byte[80];
        RvnKawpowWords.writeLE(4, header, 0);
        byte[] merkle = HexFormat.of().parseHex(
                "28ff00a867739a352523808d301f504bc4547699398d70faf2266a8bae5f3516");
        for (int i = 0; i < 32; i++) header[36 + i] = merkle[31 - i];
        RvnKawpowWords.writeLE(1514999494, header, 68);
        RvnKawpowWords.writeLE(0x1e00ffff, header, 72);
        RvnKawpowWords.writeLE(25023712, header, 76);
        var engine = new RvnJavaPowEngine(new RvnKawpowLightEngine(0));
        var hash = engine.calculate(RvnPowAlgorithm.X16R, header).blockIdWire();
        assertEquals("0000006b444bc2f2ffe627be9d9e7e7a0730000870ef6eb6da46c8eae389df90",
                new ru.bitcoin.node.rvn.consensus.RvnHash256(hash).toString());
    }
}
