package ru.bitcoin.node.rvn.protocol;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class RvnGetHeadersDecodeTest {
    @Test
    void roundTripAndDefensiveCopies() throws Exception {
        byte[] a = new byte[32];
        a[0] = 9;
        byte[] stop = new byte[32];
        stop[0] = 7;
        var request = RvnGetHeaders.decode(RvnGetHeaders.encode(70028, List.of(a), stop));
        a[0] = 1;
        stop[0] = 2;
        assertEquals(9, request.locators().get(0)[0]);
        assertEquals(7, request.stopHash()[0]);
        request.locators().get(0)[0] = 4;
        request.stopHash()[0] = 4;
        assertEquals(9, request.locators().get(0)[0]);
        assertEquals(7, request.stopHash()[0]);
        assertEquals(70028, request.protocolVersion());
    }

    @Test
    void malformedRequestsFail() throws Exception {
        byte[] valid = RvnGetHeaders.encode(70028, List.of(new byte[32]), new byte[32]);
        assertThrows(IOException.class, () -> RvnGetHeaders.decode(Arrays.copyOf(valid, valid.length - 1)));
        assertThrows(IOException.class, () -> RvnGetHeaders.decode(Arrays.copyOf(valid, valid.length + 1)));
        byte[] zero = valid.clone();
        zero[4] = 0;
        assertThrows(IOException.class, () -> RvnGetHeaders.decode(zero));
        byte[] over = valid.clone();
        over[4] = 102;
        assertThrows(IOException.class, () -> RvnGetHeaders.decode(over));
        byte[] noncanonical = new byte[4 + 3 + 32 + 32];
        noncanonical[4] = (byte) 253;
        noncanonical[5] = 1;
        noncanonical[6] = 0;
        assertThrows(IOException.class, () -> RvnGetHeaders.decode(noncanonical));
    }
}
