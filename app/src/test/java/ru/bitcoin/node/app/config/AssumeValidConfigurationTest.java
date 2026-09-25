package ru.bitcoin.node.app.config;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import static org.junit.jupiter.api.Assertions.*;

class AssumeValidConfigurationTest {
    @Test
    void blankUsesNetworkDefault() {
        var parameters = NetworkParametersRegistry.mainnet();
        assertEquals(parameters.defaultAssumeValid(), NodeConfiguration.assumeValidHash("", parameters));
    }

    @Test
    void zeroDisablesAssumeValid() {
        var hash = NodeConfiguration.assumeValidHash("0", NetworkParametersRegistry.mainnet());
        assertArrayEquals(new byte[Hash256.LENGTH], hash.bytes());
    }

    @Test
    void explicitDisplayHashIsAcceptedAndInvalidValueRejected() {
        String display = "0123456789abcdef".repeat(4);
        assertEquals(display, NodeConfiguration.assumeValidHash(display, NetworkParametersRegistry.mainnet()).toDisplayHex());
        assertThrows(IllegalArgumentException.class,
                () -> NodeConfiguration.assumeValidHash("not-a-hash", NetworkParametersRegistry.mainnet()));
    }
}
