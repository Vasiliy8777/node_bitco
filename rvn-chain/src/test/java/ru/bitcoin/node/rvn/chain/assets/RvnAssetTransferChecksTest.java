package ru.bitcoin.node.rvn.chain.assets;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.chain.RvnBlockBodyChecks.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetTransferChecks.verify;

class RvnAssetTransferChecksTest {
    private static final Function<String, RvnAssetMetadataRules.Metadata> STATE =
            n -> new RvnAssetMetadataRules.Metadata(n, 100 * COIN, 0, true, null);
    private static Transfer transfer(String name, long amount) { return new Transfer(name, amount, null, 0); }

    @Test void splitsAndMergesWithoutMutatingInputsAndReturnsImmutableTotals() {
        var inputs = new ArrayList<Payload>(List.of(new Issue("TOKEN", 10 * COIN, 0, true, null), new Owner("TOKEN!")));
        var result = verify(inputs, List.of(transfer("TOKEN", 3 * COIN), transfer("TOKEN", 7 * COIN), transfer("TOKEN!", COIN)), STATE);
        assertEquals(Map.of("TOKEN", 10 * COIN, "TOKEN!", COIN), result);
        assertEquals(2, inputs.size());
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
    }

    @Test void rejectsCreationDestructionAndCrossAssetSubstitution() {
        var input = List.of(transfer("TOKEN", COIN));
        assertThrows(SecurityException.class, () -> verify(input, List.of(), STATE));
        assertThrows(SecurityException.class, () -> verify(List.of(), input, STATE));
        assertThrows(SecurityException.class, () -> verify(input, List.of(transfer("OTHER", COIN)), STATE));
        assertThrows(SecurityException.class, () -> verify(input, List.of(transfer("TOKEN", 2 * COIN)), STATE));
    }

    @Test void rejectsInvalidUnitsOwnersMissingStateAndOverflow() {
        for (var bad : List.of(transfer("TOKEN", 1), transfer("TOKEN", -1), transfer("TOKEN", 0), transfer("TOKEN!", 2 * COIN)))
            assertThrows(SecurityException.class, () -> verify(List.of(bad), List.of(bad), STATE));
        var input = List.of(transfer("TOKEN", COIN));
        assertThrows(SecurityException.class, () -> verify(input, input, n -> null));
        assertThrows(SecurityException.class, () -> verify(input, input, n -> STATE.apply("OTHER")));
        var maximum = transfer("TOKEN", MAX_MONEY);
        assertThrows(SecurityException.class, () -> verify(List.of(maximum, maximum), List.of(maximum), STATE));
        var zeroReissue = new Reissue("TOKEN", 0, -1, true, null);
        assertEquals(Map.of(), verify(List.of(zeroReissue), List.of(), STATE));
    }

    @Test void failsClosedForMintingAndRestrictedFamilies() {
        assertThrows(UnsupportedOperationException.class, () -> verify(List.of(), List.of(new Owner("TOKEN!")), STATE));
        var restricted = transfer("$TOKEN", COIN);
        assertThrows(SecurityException.class, () -> verify(List.of(restricted), List.of(restricted), STATE));
        var sub = transfer("TOKEN/SUB", COIN);
        assertEquals(Map.of("TOKEN/SUB", COIN), verify(List.of(sub), List.of(sub), STATE));
    }

    @Test void bitElevenPreservesLegacyWrappingButBoundsCurrentInputsAndTotals() {
        var legacy = new RvnAssetMetadataRules.Activation(true, true, true, false);
        var current = new RvnAssetMetadataRules.Activation(true, true, true, true);
        Function<String, RvnAssetMetadataRules.Metadata> unitsEight =
                n -> new RvnAssetMetadataRules.Metadata(n, COIN, 8, true, null);
        var inputs = List.of(transfer("TOKEN", Long.MAX_VALUE), transfer("TOKEN", Long.MAX_VALUE), transfer("TOKEN", 4));
        var outputs = List.of(transfer("TOKEN", 2));
        assertEquals(Map.of("TOKEN", 2L), verify(inputs, outputs, unitsEight, legacy));
        assertThrows(SecurityException.class, () -> verify(inputs, outputs, unitsEight, current));
        var maximum = transfer("TOKEN", MAX_MONEY);
        assertEquals(Map.of("TOKEN", 2 * MAX_MONEY), verify(List.of(maximum, maximum), List.of(maximum, maximum), unitsEight, legacy));
        assertThrows(SecurityException.class, () -> verify(List.of(maximum, maximum), List.of(maximum, maximum), unitsEight, current));
        for (long amount : new long[] {0, -1}) {
            var bad = List.of(transfer("TOKEN", amount));
            assertThrows(SecurityException.class, () -> verify(bad, bad, unitsEight, legacy));
        }
    }

    @Test void ownerTransferDoesNotRequireBaseMetadataAndChannelTransferIsDivisibleByItsUnits() {
        var owner = List.of(transfer("TOKEN!", COIN));
        assertEquals(Map.of("TOKEN!", COIN), verify(owner, owner, n -> null));
        var channel = List.of(transfer("TOKEN~NEWS", 2 * COIN));
        assertEquals(Map.of("TOKEN~NEWS", 2 * COIN), verify(channel, channel, STATE));
    }
}
