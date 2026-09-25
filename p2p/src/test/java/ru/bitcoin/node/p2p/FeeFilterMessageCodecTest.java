package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.codec.FeeFilterMessageCodec;
import ru.bitcoin.node.p2p.message.BitcoinMessages;

import static org.junit.jupiter.api.Assertions.*;

final class FeeFilterMessageCodecTest {

    @Test
    void roundTripsBip133LittleEndianAmount() {
        long value = 123_456_789L;
        byte[] payload = FeeFilterMessageCodec.encode(value);

        assertEquals(8, payload.length);
        assertEquals(0x15, payload[0] & 0xff);
        assertEquals(0xcd, payload[1] & 0xff);
        assertEquals(0x5b, payload[2] & 0xff);
        assertEquals(0x07, payload[3] & 0xff);
        assertEquals(value, FeeFilterMessageCodec.decode(payload));
    }

    @Test
    void bitcoinMessagesUsesFeefilterCommand() {
        var wire = BitcoinMessages.feeFilter(1_000L);

        assertEquals("feefilter", wire.command());
        assertEquals(1_000L, BitcoinMessages.decodeFeeFilter(wire));
    }

    @Test
    void rejectsWrongPayloadLength() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FeeFilterMessageCodec.decode(new byte[7])
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> FeeFilterMessageCodec.decode(new byte[9])
        );
    }

    @Test
    void preservesSignedWireValueForPolicyValidation() {
        assertEquals(-1L, FeeFilterMessageCodec.decode(FeeFilterMessageCodec.encode(-1L)));
    }
}
