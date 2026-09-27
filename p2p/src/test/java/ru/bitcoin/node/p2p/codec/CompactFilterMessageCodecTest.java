package ru.bitcoin.node.p2p.codec;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompactFilterMessageCodecTest {
    @Test
    void rangeRequestIsExactBip157Layout() {
        Hash256 stop = new Hash256(sequence(32));
        var request = new CompactFilterMessageCodec.RangeRequest(0, 0x78563412L, stop);
        byte[] encoded = CompactFilterMessageCodec.encodeRangeRequest(request);
        assertEquals(37, encoded.length);
        assertArrayEquals(new byte[]{0, 0x12, 0x34, 0x56, 0x78}, java.util.Arrays.copyOf(encoded, 5));
        assertEquals(request, CompactFilterMessageCodec.decodeRangeRequest(encoded));
    }

    @Test
    void checkpointRequestIsExactBip157Layout() {
        var request = new CompactFilterMessageCodec.CheckpointRequest(0, new Hash256(sequence(32)));
        byte[] encoded = CompactFilterMessageCodec.encodeCheckpointRequest(request);
        assertEquals(33, encoded.length);
        assertEquals(request, CompactFilterMessageCodec.decodeCheckpointRequest(encoded));
    }

    @Test
    void responseVectorsUseCompactSizeAndWireOrderHashes() {
        Hash256 stop = new Hash256(sequence(32));
        Hash256 previous = new Hash256(fill(32, (byte) 0x55));
        Hash256 hash = new Hash256(fill(32, (byte) 0x66));
        byte[] payload = CompactFilterMessageCodec.encodeCfHeaders(0, stop, previous, List.of(hash));
        assertEquals(98, payload.length);
        assertEquals(1, payload[65] & 0xff);
        assertEquals(List.of(hash), CompactFilterMessageCodec.decodeHashVector(payload, 65, 2_000));
    }

    @Test
    void rejectsMalformedAndOversizedRequests() {
        assertThrows(IllegalArgumentException.class, () -> CompactFilterMessageCodec.decodeRangeRequest(new byte[36]));
        assertThrows(IllegalArgumentException.class, () -> CompactFilterMessageCodec.decodeCheckpointRequest(new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> CompactFilterMessageCodec.encodeCfHeaders(0,
                new Hash256(new byte[32]), new Hash256(new byte[32]), java.util.Collections.nCopies(2001, new Hash256(new byte[32]))));
    }

    private static byte[] sequence(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) i;
        return b;
    }

    private static byte[] fill(int n, byte v) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, v);
        return b;
    }
}
