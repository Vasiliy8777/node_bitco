package ru.bitcoin.node;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.HexUtils;
import ru.bitcoin.node.common.encoding.CompactSize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompactSizeTest {

    @Test
    void shouldEncodeValuesBelowFd() {
        assertEquals(
                "fc",
                HexUtils.encode(CompactSize.encode(252))
        );
    }

    @Test
    void shouldEncodeFd() {
        assertEquals(
                "fdfd00",
                HexUtils.encode(CompactSize.encode(253))
        );
    }

    @Test
    void shouldEncode65535() {
        assertEquals(
                "fdffff",
                HexUtils.encode(CompactSize.encode(65535))
        );
    }

    @Test
    void shouldEncode65536() {
        assertEquals(
                "fe00000100",
                HexUtils.encode(CompactSize.encode(65536))
        );
    }
    @Test
    void shouldDecodeCompactSize() {
        CompactSize.Decoded decoded =
                CompactSize.decode(
                        HexUtils.decode("fdfd00"),
                        0
                );

        assertEquals(253, decoded.value());
        assertEquals(3, decoded.bytesRead());
    }

    @Test
    void shouldRejectNonCanonicalCompactSize() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CompactSize.decode(
                        HexUtils.decode("fdfc00"),
                        0
                )
        );
    }
}
