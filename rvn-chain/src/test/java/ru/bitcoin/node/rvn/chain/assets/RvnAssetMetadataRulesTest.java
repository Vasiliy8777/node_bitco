package ru.bitcoin.node.rvn.chain.assets;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.rvn.chain.RvnBlockBodyChecks;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetMetadataRules.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.*;

class RvnAssetMetadataRulesTest {
    private static final long COIN = RvnBlockBodyChecks.COIN;
    private static final Activation ACTIVE = new Activation(true, true, true);

    @Test
    void issuanceChecksSupplyUnitsExistenceAndActivation() {
        var issue = new Issue("ASSET", 100 * COIN, 0, true, null);
        assertEquals(100 * COIN, issue(issue, ACTIVE, name -> false).supply());
        assertThrows(SecurityException.class, () -> issue(issue, ACTIVE, name -> true));
        assertThrows(SecurityException.class, () -> issue(issue, new Activation(false, false, false), name -> false));
        for (long amount : new long[]{-1, 0, COIN - 1, RvnBlockBodyChecks.MAX_MONEY + 1})
            assertThrows(SecurityException.class, () -> issue(new Issue("ASSET", amount, 0, true, null), ACTIVE, name -> false));
        assertEquals(1, issue(new Issue("ASSET", 1, 8, true, null), ACTIVE, name -> false).supply());
        assertThrows(SecurityException.class, () -> issue(new Issue("ASSET!", COIN, 0, false, null), ACTIVE, name -> false));
    }

    @Test
    void specialIssuanceParametersAndLaterDeploymentGates() {
        for (String name : new String[]{"ASSET#unique", "ASSET~news"}) {
            assertDoesNotThrow(() -> issue(new Issue(name, COIN, 0, false, null), ACTIVE, n -> false));
            assertThrows(SecurityException.class, () -> issue(new Issue(name, COIN, 0, true, null), ACTIVE, n -> false));
            assertThrows(SecurityException.class, () -> issue(new Issue(name, 2 * COIN, 0, false, null), ACTIVE, n -> false));
        }
        assertThrows(SecurityException.class, () -> issue(new Issue("ASSET~news", COIN, 0, false, null),
                new Activation(true, false, false), n -> false));
        assertThrows(SecurityException.class, () -> issue(new Issue("#QUALIFIER", COIN, 0, false, null),
                new Activation(true, true, false), n -> false));
        assertDoesNotThrow(() -> issue(new Issue("#QUALIFIER", 10 * COIN, 0, false, null), ACTIVE, n -> false));
        assertThrows(SecurityException.class, () -> issue(new Issue("#QUALIFIER", 11 * COIN, 0, false, null), ACTIVE, n -> false));
        var txid = new HashData(0x54, "12".repeat(32));
        assertThrows(SecurityException.class, () -> issue(new Issue("ASSET", COIN, 0, true, txid),
                new Activation(true, false, false), n -> false));
    }

    @Test
    void reissuePreservesMetadataChecksOldGranularityAndCanDisableFurtherReissue() {
        var originalHash = new HashData(0x12, "ab".repeat(32));
        var original = new Metadata("ASSET", 10 * COIN, 0, true, originalHash);
        var updated = reissue(new Reissue("ASSET", COIN, -1, true, null), original, ACTIVE);
        assertEquals(11 * COIN, updated.supply()); assertEquals(0, updated.units()); assertEquals(originalHash, updated.hash());
        // A units increase does not permit a fractional issuance in the same reissue.
        assertThrows(SecurityException.class, () -> reissue(new Reissue("ASSET", 1, 8, true, null), original, ACTIVE));
        var finalState = reissue(new Reissue("ASSET", 0, 8, false, null), updated, ACTIVE);
        assertEquals(8, finalState.units()); assertFalse(finalState.reissuable());
        assertTrue(updated.reissuable()); assertEquals(0, updated.units());
        assertThrows(SecurityException.class, () -> reissue(new Reissue("ASSET", 1, -1, false, null), finalState, ACTIVE));
        assertThrows(SecurityException.class, () -> reissue(new Reissue("OTHER", COIN, -1, true, null), original, ACTIVE));
        assertThrows(SecurityException.class, () -> reissue(new Reissue("ASSET", 0, 7, true, null),
                new Metadata("ASSET", COIN, 8, true, null), ACTIVE));
    }

    @Test
    void supplyBoundaryNeverOverflowsAndGranularityUsesIntegerArithmetic() {
        var original = new Metadata("ASSET", RvnBlockBodyChecks.MAX_MONEY - 1, 8, true, null);
        assertEquals(RvnBlockBodyChecks.MAX_MONEY,
                reissue(new Reissue("ASSET", 1, -1, true, null), original, ACTIVE).supply());
        assertThrows(SecurityException.class, () -> reissue(new Reissue("ASSET", Long.MAX_VALUE, -1, true, null), original, ACTIVE));
        assertThrows(SecurityException.class, () -> reissue(new Reissue("ASSET", 2, -1, true, null), original, ACTIVE));
        assertTrue(amountMatchesUnits(COIN / 100, 2));
        assertFalse(amountMatchesUnits(COIN / 100 + 1, 2));
        assertFalse(amountMatchesUnits(0, -1)); assertFalse(amountMatchesUnits(0, 9));
    }
}
