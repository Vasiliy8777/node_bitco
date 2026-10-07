package ru.bitcoin.node.app.config;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class MiningPayoutResolverTest {
    @Test
    void stratumDoesNotPayPublicRewardsToAnyoneCanSpendScript() {
        assertThrows(IllegalArgumentException.class, () -> MiningPayoutResolver.resolveStratum(
                "", "51", NetworkParametersRegistry.mainnet()));
        assertArrayEquals(new byte[]{0x51}, MiningPayoutResolver.resolveStratum(
                "", "51", NetworkParametersRegistry.regtest()));
    }
    @Test
    void payoutAddressTakesPrecedenceOverLegacyScript() {
        byte[] script = MiningPayoutResolver.resolve(
                "bc1qpjf8xhuttzemn7hla024klrrftqqp607zphkyf", "51", NetworkParametersRegistry.mainnet());
        assertEquals("00140c92735f8b58b3b9faffebd55b7c634ac000e9fe", HexFormat.of().formatHex(script));
    }

    @Test
    void keepsLegacyRawScriptCompatibility() {
        assertArrayEquals(new byte[]{0x51}, MiningPayoutResolver.resolve(
                "", "51", NetworkParametersRegistry.regtest()));
    }

    @Test
    void rejectsWrongNetworkBeforeMiningStarts() {
        assertThrows(IllegalArgumentException.class, () -> MiningPayoutResolver.resolve(
                "bc1qpjf8xhuttzemn7hla024klrrftqqp607zphkyf", "51", NetworkParametersRegistry.testnet4()));
    }

    @Test
    void requiresAddressOrScript() {
        assertThrows(IllegalArgumentException.class, () -> MiningPayoutResolver.resolve(
                "", "", NetworkParametersRegistry.mainnet()));
    }
}
