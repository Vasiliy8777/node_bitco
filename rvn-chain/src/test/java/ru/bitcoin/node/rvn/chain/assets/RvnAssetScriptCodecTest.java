package ru.bitcoin.node.rvn.chain.assets;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.*;

class RvnAssetScriptCodecTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] LOCK = HEX.parseHex("76a914000000000000000000000000000000000000000088ac");
    private static final HashData HASH = new HashData(0x12, "ab".repeat(32));

    @Test
    void fixedHexForFourCoreConstructorFormats() {
        var payloads = List.<Payload>of(new Issue("ABC", 100_000_000, 0, true, null), new Owner("ABC!"),
                new Transfer("ABC", 100_000_000, null, 0), new Reissue("ABC", 0, -1, false, null));
        var suffixes = List.of("c01372766e710341424300e1f5050000000000010075", "c00972766e6f044142432175",
                "c01072766e740341424300e1f5050000000075", "c01272766e72034142430000000000000000ff0075");
        for (int i = 0; i < payloads.size(); i++) {
            byte[] expected = HEX.parseHex(HEX.formatHex(LOCK) + suffixes.get(i));
            assertArrayEquals(expected, encodeCanonical(LOCK, payloads.get(i)));
            var decoded = decodeCanonical(expected).orElseThrow();
            assertArrayEquals(LOCK, decoded.lockingScript()); assertEquals(payloads.get(i), decoded.payload());
        }
    }

    @Test
    void hashMessagesReissueSentinelAndPushdataBoundary() {
        var payloads = List.<Payload>of(new Issue("LONG_ASSET_NAME_123456789012345", Long.MAX_VALUE, 8, false, HASH),
                new Issue("ABC", -1, 0, true, new HashData(0x54, "12".repeat(32))),
                new Transfer("ABC", 1, HASH, 1_800_000_000), new Reissue("ABC", 0, -1, true, HASH));
        for (var payload : payloads) {
            byte[] script = encodeCanonical(LOCK, payload);
            assertEquals(payload, decodeCanonical(script).orElseThrow().payload());
        }
        assertEquals(0x4c, encodeCanonical(LOCK, payloads.getFirst())[26] & 255);
        byte[] script = encodeCanonical(LOCK, new Transfer("ABC", 1, null, 0));
        var decoded = decodeCanonical(script).orElseThrow();
        decoded.lockingScript()[0] = 0; script[0] = 0;
        assertEquals(0x76, decoded.lockingScript()[0] & 255);
        assertThrows(IllegalArgumentException.class, () -> encodeCanonical(LOCK, new Transfer("ABC", 1, null, 1)));
    }

    @Test
    void rejectsTruncationNonminimalPushUnknownTypesAndTrailingData() {
        byte[] valid = encodeCanonical(LOCK, new Issue("ABC", 100_000_000, 0, true, HASH));
        for (int end = 26; end < valid.length; end++) {
            byte[] truncated = Arrays.copyOf(valid, end);
            assertThrows(IllegalArgumentException.class, () -> decodeCanonical(truncated), "length " + end);
        }
        byte[] extra = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IllegalArgumentException.class, () -> decodeCanonical(extra));
        byte[] unknown = valid.clone(); unknown[30] = 'x';
        assertThrows(UnsupportedOperationException.class, () -> decodeCanonical(unknown));
        byte[] badBoolean = valid.clone(); badBoolean[44] = 2;
        assertThrows(IllegalArgumentException.class, () -> decodeCanonical(badBoolean));
        byte[] nonminimal = new byte[valid.length + 1];
        System.arraycopy(valid, 0, nonminimal, 0, 26); nonminimal[26] = 76;
        System.arraycopy(valid, 26, nonminimal, 27, valid.length - 26);
        assertThrows(IllegalArgumentException.class, () -> decodeCanonical(nonminimal));
    }

    @Test
    void ordinaryScriptsAndUnsupportedNullAssetFormStayDistinct() {
        assertTrue(decodeCanonical(LOCK).isEmpty());
        assertTrue(decodeCanonical(new byte[]{0x51}).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> decodeCanonical(new byte[]{(byte) 0xc0, 0x50, 0}));
        assertThrows(IllegalArgumentException.class, () -> encodeCanonical(new byte[]{0x51}, new Owner("ABC!")));
    }
}
