package ru.bitcoin.node.rvn.protocol;

import org.junit.jupiter.api.Test;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

class RvnWireTest {
    @Test
    void networkConstants() {
        assertArrayEquals(new byte[]{0x52, 0x41, 0x56, 0x4e}, RvnNetwork.MAINNET.magic());
        assertEquals(8767, RvnNetwork.MAINNET.port());
        assertEquals(18770, RvnNetwork.TESTNET.port());
    }

    @Test
    void roundTrip() throws Exception {
        var out = new ByteArrayOutputStream();
        RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("ping", new byte[]{1, 2, 3}));
        var msg = RvnWire.read(new ByteArrayInputStream(out.toByteArray()), RvnNetwork.MAINNET);
        assertEquals("ping", msg.command());
        assertArrayEquals(new byte[]{1, 2, 3}, msg.payload());
    }

    @Test
    void checksumMismatch() throws Exception {
        var out = new ByteArrayOutputStream();
        RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("ping", new byte[]{1}));
        byte[] b = out.toByteArray();
        b[b.length - 1] ^= 1;
        assertThrows(IOException.class, () -> RvnWire.read(new ByteArrayInputStream(b), RvnNetwork.MAINNET));
    }

    @Test
    void wrongNetwork() throws Exception {
        var out = new ByteArrayOutputStream();
        RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("verack", new byte[0]));
        assertThrows(IOException.class, () -> RvnWire.read(new ByteArrayInputStream(out.toByteArray()), RvnNetwork.TESTNET));
    }

    @Test
    void truncated() {
        assertThrows(EOFException.class, () -> RvnWire.read(new ByteArrayInputStream(new byte[]{1, 2}), RvnNetwork.MAINNET));
    }

    @Test
    void oversized() throws Exception {
        var out = new ByteArrayOutputStream();
        out.write(RvnNetwork.MAINNET.magic());
        out.write(new byte[]{'p', 'i', 'n', 'g'});
        out.write(new byte[8]);
        RvnWire.writeU32(out, RvnWire.MAX_PAYLOAD + 1);
        assertThrows(IOException.class, () -> RvnWire.read(new ByteArrayInputStream(out.toByteArray()), RvnNetwork.MAINNET));
    }
}
