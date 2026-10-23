package ru.bitcoin.node.rvn.app.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.rvn.consensus.*;
import ru.bitcoin.node.rvn.pow.*;
import ru.bitcoin.node.rvn.protocol.*;
import ru.bitcoin.node.rvn.storage.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class RvnMainnetHeaderChainTest {
    @TempDir Path temp;
    // Deliberately test-only: positive synthetic chains exercise storage/context plumbing.
    public static final RvnPowEngine TEST_POW = (algorithm, raw) -> new RvnPowResult(
            RvnWire.doubleSha(raw), new byte[32]);
    public static RvnHeaderStore open(Path path) throws Exception {
        return new RvnHeaderStore(path, RvnMainnetExpectedBits.GENESIS_ID,
                RvnHistoricalPowSelfTest.mainnetGenesisHeader(), RvnMainnetDifficulty.POW_LIMIT);
    }
    public static byte[] child(RvnHash256 parent, long time) {
        byte[] raw = new byte[80]; raw[0] = 4;
        System.arraycopy(parent.wire(), 0, raw, 4, 32);
        RvnKawpowWords.writeLE((int) time, raw, 68); RvnKawpowWords.writeLE(0x1e00ffff, raw, 72);
        return raw;
    }
    public static RvnBlockHeader parsed(byte[] raw) throws Exception {
        return RvnBlockHeader.parse(raw, RvnActivation.MAINNET_KAWPOW_TIME);
    }

    @Test
    void contextualBatchRejectsWrongBitsTimeParentAndHeightWithoutPartialCommit() throws Exception {
        try (var store = open(temp.resolve("headers"))) {
            var chain = new RvnMainnetHeaderChain(store, TEST_POW, () -> 1515000000L);
            byte[] first = child(RvnMainnetExpectedBits.GENESIS_ID, 1514999554L);
            byte[] second = child(new RvnHash256(RvnWire.doubleSha(first)), 1514999614L);
            byte[] badBits = second.clone(); RvnKawpowWords.writeLE(0x1e00fffe, badBits, 72);
            assertThrows(SecurityException.class, () -> chain.accept(List.of(parsed(first), parsed(badBits))));
            assertEquals(0, chain.best().height()); assertEquals(1, store.count());
            byte[] old = first.clone(); RvnKawpowWords.writeLE(1514999494, old, 68);
            assertThrows(SecurityException.class, () -> chain.accept(List.of(parsed(old))));
            byte[] future = first.clone(); RvnKawpowWords.writeLE(1515007201, future, 68);
            assertThrows(SecurityException.class, () -> chain.accept(List.of(parsed(future))));
            byte[] kawpow = new byte[120]; System.arraycopy(first, 0, kawpow, 0, 76);
            RvnKawpowWords.writeLE(1588788000, kawpow, 68); RvnKawpowWords.writeLE(99, kawpow, 76);
            assertThrows(SecurityException.class, () -> chain.accept(List.of(parsed(kawpow))));
            var result = chain.accept(List.of(parsed(first), parsed(second)));
            assertEquals(2, result.added()); assertEquals(2, result.best().height());
            assertEquals(new RvnHash256(RvnWire.doubleSha(second)), result.lastId());
        }
        try (var reopened = open(temp.resolve("headers"))) { assertEquals(2, reopened.best().height()); }
    }

    @Test
    void actualJavaPowRejectsSyntheticHeaderAndNeverMovesTip() throws Exception {
        try (var store = open(temp.resolve("headers"))) {
            var real = new RvnJavaPowEngine(new RvnKawpowLightEngine(0));
            var chain = new RvnMainnetHeaderChain(store, real, () -> 1515000000L);
            byte[] raw = child(RvnMainnetExpectedBits.GENESIS_ID, 1514999554L);
            assertFalse(RvnPowDigest.meetsTarget(real.calculate(RvnPowAlgorithm.X16R, raw).powDigestWire(),
                    RvnPowDigest.ByteOrder.LITTLE_ENDIAN, 0x1e00ffffL, RvnMainnetDifficulty.POW_LIMIT));
            assertThrows(SecurityException.class, () -> chain.accept(List.of(parsed(raw))));
            assertEquals(1, store.count()); assertEquals(0, store.best().height());
        }
    }
}
