package ru.bitcoin.node.rvn.protocol;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RvnHeadersTest {
    private static byte[] header(boolean kawpow, long timestamp, int height) {
        byte[] b = new byte[kawpow ? 120 : 80];
        b[0] = 4;
        for (int i = 0; i < 4; i++) b[68+i] = (byte)(timestamp >>> (8*i));
        b[72] = (byte)0xff; b[73] = (byte)0xff; b[74] = 0; b[75] = 0x1e;
        for (int i = 0; i < 4; i++) b[76+i] = (byte)(height >>> (8*i));
        return b;
    }
    @Test void legacyAndKawpowRoundTrip() throws Exception {
        long activation = RvnActivation.MAINNET_KAWPOW_TIME;
        RvnBlockHeader old = RvnBlockHeader.parse(header(false, activation-1, 42), activation);
        RvnBlockHeader now = RvnBlockHeader.parse(header(true, activation, 100), activation);
        assertFalse(old.isKawpow());
        assertEquals(42, old.legacyNonce());
        assertTrue(now.isKawpow());
        assertEquals(100, now.declaredHeight());
        assertEquals(32, now.mixHashWire().length);
        assertEquals(32, now.kawpowInputDigest().length);
        byte[] bytes = RvnHeaders.encode(List.of(old, now));
        assertEquals(2, RvnHeaders.decode(bytes, activation).size());
        assertArrayEquals(bytes, RvnHeaders.encode(RvnHeaders.decode(bytes, activation)));
    }
    @Test void rejectsTruncatedAndNonzeroTransactionCount() throws Exception {
        long activation = RvnActivation.MAINNET_KAWPOW_TIME;
        byte[] bytes = RvnHeaders.encode(List.of(RvnBlockHeader.parse(header(true, activation, 7), activation)));
        bytes[bytes.length-1] = 1;
        assertThrows(IOException.class, () -> RvnHeaders.decode(bytes, activation));
        byte[] truncated = Arrays.copyOf(bytes, bytes.length-2);
        assertThrows(IOException.class, () -> RvnHeaders.decode(truncated, activation));
    }
    @Test void rejectsTrailingBytesAndExcessiveCount() throws Exception {
        assertThrows(IOException.class, () -> RvnHeaders.decode(new byte[]{0, 0}, 0));
        assertThrows(IOException.class, () -> RvnHeaders.decode(new byte[]{(byte)253, (byte)0xd1, 7}, 0));
    }
    @Test void checksDeclaredHeightSequence() throws Exception {
        long activation = RvnActivation.MAINNET_KAWPOW_TIME;
        var first = RvnBlockHeader.parse(header(true, activation, 12), activation);
        var wrong = RvnBlockHeader.parse(header(true, activation, 14), activation);
        assertThrows(IOException.class, () -> RvnHeaderRules.checkBatch(List.of(first, wrong)));
    }
    @Test void defensiveCopy() throws Exception {
        byte[] raw = header(true, RvnActivation.MAINNET_KAWPOW_TIME, 12);
        RvnBlockHeader h = RvnBlockHeader.parse(raw, RvnActivation.MAINNET_KAWPOW_TIME);
        raw[0] = 100;
        assertEquals(4, h.version());
        byte[] copy = h.serialize(); copy[0] = 100;
        assertEquals(4, h.version());
    }
}
