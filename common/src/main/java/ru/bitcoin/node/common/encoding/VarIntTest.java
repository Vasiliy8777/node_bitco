package ru.bitcoin.node.common.encoding;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.HexUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VarIntTest {

    @Test
    void shouldEncodeSmallValues() {
        assertEquals(
                "00",
                HexUtils.encode(VarInt.encode(0))
        );

        assertEquals(
                "01",
                HexUtils.encode(VarInt.encode(1))
        );

        assertEquals(
                "7f",
                HexUtils.encode(VarInt.encode(127))
        );
    }

    @Test
    void shouldEncode128() {
        assertEquals(
                "8000",
                HexUtils.encode(VarInt.encode(128))
        );
    }

    @Test
    void shouldEncode255() {
        assertEquals(
                "807f",
                HexUtils.encode(VarInt.encode(255))
        );
    }

    @Test
    void shouldEncode256() {
        assertEquals(
                "8100",
                HexUtils.encode(VarInt.encode(256))
        );
    }

    @Test
    void shouldDecodeValues() {
        assertRoundTrip(0);
        assertRoundTrip(1);
        assertRoundTrip(127);
        assertRoundTrip(128);
        assertRoundTrip(255);
        assertRoundTrip(256);
        assertRoundTrip(16_383);
        assertRoundTrip(16_384);
        assertRoundTrip(1_000_000);
        assertRoundTrip(Integer.MAX_VALUE);
        assertRoundTrip(Long.MAX_VALUE);
    }

    private void assertRoundTrip(long value) {
        byte[] encoded = VarInt.encode(value);

        VarInt.Decoded decoded =
                VarInt.decode(encoded, 0);

        assertEquals(value, decoded.value());
        assertEquals(encoded.length, decoded.bytesRead());
    }
}
