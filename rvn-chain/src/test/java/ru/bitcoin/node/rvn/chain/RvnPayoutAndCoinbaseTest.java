package ru.bitcoin.node.rvn.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;
import ru.bitcoin.node.protocol.serialization.*;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RvnPayoutAndCoinbaseTest {
    static final String PUBLIC_CORE_ADDRESS = "RXBurnXXXXXXXXXXXXXXXXXXXXXXWUo9FV";
    static String encoded(int version, byte[] hash) {
        byte[] bytes = new byte[25]; bytes[0] = (byte) version; System.arraycopy(hash, 0, bytes, 1, 20);
        System.arraycopy(ru.bitcoin.node.rvn.protocol.RvnWire.doubleSha(Arrays.copyOf(bytes, 21)), 0, bytes, 21, 4);
        var value = new BigInteger(1, bytes); var text = new StringBuilder();
        String alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
        while (value.signum() > 0) {
            var division = value.divideAndRemainder(BigInteger.valueOf(58));
            text.append(alphabet.charAt(division[1].intValue())); value = division[0];
        }
        for (byte b : bytes) { if (b != 0) break; text.append('1'); }
        return text.reverse().toString();
    }

    @Test
    void networkPrefixesScriptsChecksumsAndNoAddressInDiagnostics() {
        byte[] hash = new byte[20]; Arrays.fill(hash, (byte) 0xa5);
        String address = encoded(60, hash);
        var payout = RvnPayoutAddress.parse(address, RvnNetwork.MAINNET);
        assertEquals(RvnPayoutAddress.Type.P2PKH, payout.type());
        assertEquals("76a914" + HexFormat.of().formatHex(hash) + "88ac", HexFormat.of().formatHex(payout.scriptPubKey()));
        payout.scriptPubKey()[0] = 0; assertEquals(0x76, payout.scriptPubKey()[0]);
        assertEquals("a914" + HexFormat.of().formatHex(hash) + "87",
                HexFormat.of().formatHex(RvnPayoutAddress.parse(encoded(122, hash), RvnNetwork.MAINNET).scriptPubKey()));
        assertDoesNotThrow(() -> RvnPayoutAddress.parse(encoded(111, hash), RvnNetwork.TESTNET));
        assertDoesNotThrow(() -> RvnPayoutAddress.parse(encoded(196, hash), RvnNetwork.REGTEST));
        assertThrows(IllegalArgumentException.class, () -> RvnPayoutAddress.parse(address, RvnNetwork.TESTNET));
        assertThrows(IllegalArgumentException.class, () -> RvnPayoutAddress.parse(encoded(0, hash), RvnNetwork.MAINNET));
        String bad = address.substring(0, address.length() - 1) + (address.endsWith("1") ? "2" : "1");
        var failure = assertThrows(IllegalArgumentException.class, () -> RvnPayoutAddress.parse(bad, RvnNetwork.MAINNET));
        assertFalse(failure.getMessage().contains(bad)); assertFalse(payout.toString().contains(address));
        assertDoesNotThrow(() -> RvnPayoutAddress.parse(PUBLIC_CORE_ADDRESS, RvnNetwork.MAINNET));
    }

    @Test
    void coinbasePaysConfiguredScriptUsesRvnHalvingsAndChangesTxidWithExtraNonce() {
        var payout = RvnPayoutAddress.parse(PUBLIC_CORE_ADDRESS, RvnNetwork.MAINNET);
        long coin = RvnBlockBodyChecks.COIN;
        assertEquals(5000 * coin, RvnCoinbase.subsidy(2_099_999));
        assertEquals(2500 * coin, RvnCoinbase.subsidy(2_100_000));
        assertEquals(1250 * coin, RvnCoinbase.subsidy(4_200_000));
        assertEquals(0, RvnCoinbase.subsidy(2_100_000L * 64));
        var tx = RvnCoinbase.create(4_200_000, 12345, 500, payout);
        assertTrue(tx.isCoinbase()); assertEquals(1250 * coin + 500, tx.outputs().getFirst().value());
        assertArrayEquals(payout.scriptPubKey(), tx.outputs().getFirst().scriptPubKey());
        assertEquals(tx, TransactionParser.parse(TransactionSerializer.serialize(tx)));
        assertNotEquals(tx.txId(), RvnCoinbase.create(4_200_000, 12346, 500, payout).txId());
        byte[] sig = tx.inputs().getFirst().scriptSig();
        assertArrayEquals(new byte[]{3, 0x40, 0x16, 0x40}, Arrays.copyOf(sig, 4));
        assertTrue(sig.length >= 2 && sig.length <= 100);
        assertThrows(IllegalArgumentException.class, () -> RvnCoinbase.create(0, 0, 0, payout));
        assertThrows(IllegalArgumentException.class, () -> RvnCoinbase.create(1, 0, -1, payout));
        assertThrows(IllegalArgumentException.class, () -> RvnCoinbase.create(1, 0, RvnBlockBodyChecks.MAX_MONEY, payout));
    }
}
