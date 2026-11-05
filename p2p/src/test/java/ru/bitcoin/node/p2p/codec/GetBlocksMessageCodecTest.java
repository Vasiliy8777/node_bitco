package ru.bitcoin.node.p2p.codec;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.message.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GetBlocksMessageCodecTest {
    @Test void roundTripsNullAndMaximumLocatorsWithTheCorrectCommand() {
        var stop = Hash256.fromDisplayHex("12".repeat(32));
        for (var locator : List.of(List.<Hash256>of(), Collections.nCopies(101, stop))) {
            var message = new GetBlocksMessage(70016, locator, stop);
            var wire = BitcoinMessages.getBlocks(message);
            assertEquals("getblocks", wire.command());
            assertEquals(message, BitcoinMessages.decodeGetBlocks(wire));
        }
    }

    @Test void preservesStrictFramingAndDistinguishesOversizedLocators() {
        var stop = new Hash256(new byte[32]);
        var wire = GetBlocksMessageCodec.encode(new GetBlocksMessage(70016, List.of(), stop));
        assertEquals(37, wire.length);
        assertThrows(IllegalArgumentException.class, () -> GetBlocksMessageCodec.decode(Arrays.copyOf(wire, 36)));
        assertThrows(IllegalArgumentException.class, () -> GetBlocksMessageCodec.decode(Arrays.copyOf(wire, 38)));
        var oversized = wire.clone(); oversized[4] = 102;
        assertThrows(GetHeadersMessageCodec.TooManyLocatorHashesException.class, () -> GetBlocksMessageCodec.decode(oversized));
        var nonCanonical = new byte[39];
        System.arraycopy(wire, 0, nonCanonical, 0, 4); nonCanonical[4] = (byte) 0xfd;
        System.arraycopy(wire, 5, nonCanonical, 7, 32);
        assertThrows(IllegalArgumentException.class, () -> GetBlocksMessageCodec.decode(nonCanonical));
    }
}
