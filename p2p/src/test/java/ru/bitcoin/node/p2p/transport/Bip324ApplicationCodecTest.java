package ru.bitcoin.node.p2p.transport;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.message.BitcoinMessage;

import static org.junit.jupiter.api.Assertions.*;

class Bip324ApplicationCodecTest {
    @Test
    void usesSpecifiedShortIds() {
        byte[] encoded = Bip324ApplicationCodec.encode(new BitcoinMessage("tx", new byte[]{1, 2, 3}));
        assertArrayEquals(new byte[]{21, 1, 2, 3}, encoded);
        BitcoinMessage decoded = Bip324ApplicationCodec.decode(encoded);
        assertEquals("tx", decoded.command());
        assertArrayEquals(new byte[]{1, 2, 3}, decoded.payload());
    }

    @Test
    void acceptsLongEncodingEvenForKnownCommand() {
        byte[] encoded = new byte[14];
        encoded[0] = 0;
        encoded[1] = 'p';
        encoded[2] = 'i';
        encoded[3] = 'n';
        encoded[4] = 'g';
        encoded[13] = 9;
        BitcoinMessage decoded = Bip324ApplicationCodec.decode(encoded);
        assertEquals("ping", decoded.command());
        assertArrayEquals(new byte[]{9}, decoded.payload());
    }

    @Test
    void unknownCommandUsesLongEncoding() {
        byte[] encoded = Bip324ApplicationCodec.encode(new BitcoinMessage("sendheaders", new byte[0]));
        assertEquals(0, encoded[0]);
        assertEquals("sendheaders", Bip324ApplicationCodec.decode(encoded).command());
    }

    @Test
    void rejectsUndefinedShortId() {
        assertThrows(IllegalArgumentException.class, () -> Bip324ApplicationCodec.decode(new byte[]{29}));
    }
}
