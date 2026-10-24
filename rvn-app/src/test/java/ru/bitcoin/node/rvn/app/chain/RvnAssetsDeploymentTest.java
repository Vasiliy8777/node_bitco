package ru.bitcoin.node.rvn.app.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.rvn.consensus.RvnHash256;
import ru.bitcoin.node.rvn.pow.RvnKawpowWords;
import ru.bitcoin.node.rvn.protocol.RvnWire;
import ru.bitcoin.node.rvn.storage.*;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RvnAssetsDeploymentTest {
    private final Map<RvnHash256, RvnStoredHeader> entries = new HashMap<>();
    private final RvnHeaderLookup lookup = entries::get;

    private RvnStoredHeader append(RvnStoredHeader parent, int version, long time) throws Exception {
        byte[] raw = new byte[time >= ru.bitcoin.node.rvn.protocol.RvnActivation.MAINNET_KAWPOW_TIME ? 120 : 80];
        RvnKawpowWords.writeLE(version, raw, 0); RvnKawpowWords.writeLE((int) time, raw, 68);
        RvnKawpowWords.writeLE(0x1e00ffff, raw, 72);
        long height = parent == null ? 0 : parent.height() + 1;
        if (parent != null) System.arraycopy(parent.id().wire(), 0, raw, 4, 32);
        var id = new RvnHash256(RvnWire.doubleSha(raw));
        var skip = parent == null ? id : lookup.ancestor(parent, RvnHeaderLookup.skipHeight(height)).id();
        var entry = new RvnStoredHeader(id, height, BigInteger.valueOf(height + 1), skip, raw);
        entries.put(id, entry); return entry;
    }

    @Test
    void activatesOnlyAfterSignallingAndOneLockedInWindowAndCachesByBranch() throws Exception {
        var deployment = new RvnAssetsDeployment(4, 3, 100, 10000);
        RvnStoredHeader cursor = null;
        for (int height = 0; height < 4; height++) cursor = append(cursor, 4, 200 + height);
        var forkPoint = cursor;
        assertEquals(RvnAssetsDeployment.State.STARTED, deployment.stateForNext(cursor, lookup));
        for (int height = 4; height < 8; height++) {
            // Raven counts the bit even without Bitcoin's version top-mask prefix.
            cursor = append(cursor, height == 4 ? 4 : 0x40000040, 200 + height);
            if (height < 7) assertFalse(deployment.activeForNext(cursor, lookup));
        }
        assertEquals(RvnAssetsDeployment.State.LOCKED_IN, deployment.stateForNext(cursor, lookup));
        for (int height = 8; height < 12; height++) cursor = append(cursor, 4, 200 + height);
        assertTrue(deployment.activeForNext(cursor, lookup));
        var alternate = forkPoint;
        for (int height = 4; height < 12; height++) alternate = append(alternate, 4, 300 + height);
        assertEquals(RvnAssetsDeployment.State.STARTED, deployment.stateForNext(alternate, lookup));
        assertTrue(deployment.activeForNext(cursor, lookup));
    }

    @Test
    void timeoutAndPreStartBoundaries() throws Exception {
        RvnStoredHeader cursor = null;
        for (int height = 0; height < 4; height++) cursor = append(cursor, 0x40, 200 + height);
        assertEquals(RvnAssetsDeployment.State.FAILED,
                new RvnAssetsDeployment(4, 3, 100, 150).stateForNext(cursor, lookup));
        assertEquals(RvnAssetsDeployment.State.DEFINED,
                new RvnAssetsDeployment(4, 3, 1000, 2000).stateForNext(cursor, lookup));
    }

    @Test
    void deploymentBitsAreIndependentAndLockedInIsDistinctFromActive() throws Exception {
        var rip5 = new RvnAssetsDeployment(4, 3, 100, 10000, 7);
        var value = new RvnAssetsDeployment(4, 3, 100, 10000, 9);
        RvnStoredHeader cursor = null;
        for (int height = 0; height < 8; height++) cursor = append(cursor, 1 << 9, 200 + height);
        assertEquals(RvnAssetsDeployment.State.LOCKED_IN, value.stateForNext(cursor, lookup));
        assertFalse(value.activeForNext(cursor, lookup));
        assertEquals(RvnAssetsDeployment.State.STARTED, rip5.stateForNext(cursor, lookup));
        for (int height = 8; height < 12; height++) cursor = append(cursor, 0, 200 + height);
        assertTrue(value.activeForNext(cursor, lookup)); assertFalse(rip5.activeForNext(cursor, lookup));
    }

    @Test
    void mainnetOverflowStartsAtBitElevenAndOnlyEnforcesAfterLockedInWindow() throws Exception {
        var context = new RvnAssetDeploymentContext();
        RvnStoredHeader cursor = null;
        for (int height = 0; height < 2016; height++) cursor = append(cursor, 1 << 11, 1781222401L + height);
        var fork = cursor;
        assertFalse(context.forNext(cursor, lookup).activation().transferOverflow());
        for (int height = 2016; height < 4032; height++) cursor = append(cursor, 1 << 11, 1781222401L + height);
        assertFalse(context.forNext(cursor, lookup).activation().transferOverflow());
        for (int height = 4032; height < 6048; height++) cursor = append(cursor, 0, 1781222401L + height);
        assertTrue(context.forNext(cursor, lookup).activation().transferOverflow());
        var alternate = fork;
        for (int height = 2016; height < 6048; height++) alternate = append(alternate, 1 << 10, 1781223401L + height);
        assertFalse(context.forNext(alternate, lookup).activation().transferOverflow());
        assertTrue(context.forNext(cursor, lookup).activation().transferOverflow());
    }
}
