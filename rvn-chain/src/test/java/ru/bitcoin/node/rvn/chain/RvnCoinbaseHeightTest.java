package ru.bitcoin.node.rvn.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RvnCoinbaseHeightTest {
    @Test
    void exactMinimalHeightPrefixesAndSignPadding() {
        var payout = RvnPayoutAddress.parse("RXBurnXXXXXXXXXXXXXXXXXXXXXXWUo9FV", RvnNetwork.MAINNET);
        long[] heights = {1, 16, 17, 127, 128, 255, 256, 8_388_607, 8_388_608};
        String[] prefixes = {"51", "60", "0111", "017f", "028000", "02ff00", "020001", "03ffff7f", "0400008000"};
        for (int i = 0; i < heights.length; i++) {
            long height = heights[i];
            var coinbase = RvnCoinbase.create(height, 0, 0, payout);
            byte[] expected = HexFormat.of().parseHex(prefixes[i]);
            assertArrayEquals(expected, Arrays.copyOf(coinbase.inputs().getFirst().scriptSig(), expected.length));
            assertDoesNotThrow(() -> RvnCoinbase.requireHeight(coinbase, height));
            assertThrows(SecurityException.class, () -> RvnCoinbase.requireHeight(coinbase, height + 1));
        }
    }

    @Test
    void nonminimalTruncatedAndMissingHeightAreRejected() {
        var payout = RvnPayoutAddress.parse("RXBurnXXXXXXXXXXXXXXXXXXXXXXWUo9FV", RvnNetwork.MAINNET);
        var original = RvnCoinbase.create(1, 0, 0, payout);
        for (byte[] script : List.of(new byte[0], new byte[]{1, 1}, new byte[]{2, 1, 0})) {
            var tx = new Transaction(1, List.of(new TxIn(OutPoint.coinbase(), script, TxIn.FINAL_SEQUENCE)),
                    original.outputs(), new UInt32(0));
            assertThrows(SecurityException.class, () -> RvnCoinbase.requireHeight(tx, 1));
        }
    }
}
