package ru.bitcoin.node.rvn.chain.assets;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.*;

class RvnHistoricalAssetCodecTest {
    private static byte[] lock() {
        byte[] lock = new byte[25]; lock[0] = 0x76; lock[1] = (byte) 0xa9; lock[2] = 20;
        lock[23] = (byte) 0x88; lock[24] = (byte) 0xac; return lock;
    }
    @Test void positionalClassifierDoesNotRequireCanonicalPrefixPushOrDrop() {
        byte[] script = encodeCanonical(lock(), new Issue("TOKEN", 100000000, 0, true, null));
        script[0] = 0; script[26] = (byte) 0xff; script[script.length-1] = (byte) 0xab;
        var decoded = RvnHistoricalAssetCodec.decode(script, true).orElseThrow();
        assertEquals("TOKEN", decoded.name()); assertEquals(100000000, decoded.amount());
        assertTrue(decodeCanonical(script).isEmpty());
        assertEquals(31, decoded.classification().dataOffset());
    }
    @Test void transferOffsetFixIsEraDependentAndTrailingBytesAreIgnored() {
        byte[] canonical = encodeCanonical(lock(), new Transfer("TOKEN", 100000000, null, 0));
        byte[] wide = new byte[canonical.length + 1];
        System.arraycopy(canonical, 0, wide, 0, 26); wide[26] = 76; wide[27] = canonical[26];
        System.arraycopy(canonical, 27, wide, 28, canonical.length-27);
        assertEquals("TOKEN", RvnHistoricalAssetCodec.decode(wide, true).orElseThrow().name());
        assertThrows(IllegalArgumentException.class, () -> RvnHistoricalAssetCodec.decode(wide, false));
        byte[] trailing = Arrays.copyOf(canonical, canonical.length+5);
        assertEquals(0, RvnHistoricalAssetCodec.decode(trailing, true).orElseThrow().hash().length);
        assertThrows(IllegalArgumentException.class, () -> decodeCanonical(trailing));
    }
    @Test void optionalHashesFollowCoreLengthMarkerAndTruncationBehavior() {
        byte[] base = encodeCanonical(lock(), new Transfer("TOKEN", 100000000, null, 0));
        var out = new ByteArrayOutputStream(); out.writeBytes(Arrays.copyOf(base, base.length-1));
        out.write(0x99); out.write(33); out.writeBytes(new byte[33]);
        var decoded = RvnHistoricalAssetCodec.decode(out.toByteArray(), true).orElseThrow();
        assertEquals(32, decoded.hash().length); // Non-IPFS marker becomes raw txid bytes in Core.
        byte[] mutation = decoded.hash(); mutation[0] = 1; assertEquals(0, decoded.hash()[0]);
        byte[] issue = encodeCanonical(lock(), new Issue("TOKEN", 100000000, 0, true, null));
        issue[issue.length-2] = 1; // hasHash=1 with insufficient remaining bytes.
        var partial = RvnHistoricalAssetCodec.decode(issue, true).orElseThrow();
        assertEquals(1, partial.hasHash()); assertEquals(0, partial.hash().length);
        // Deserialization preserves this distinction for subsequent validity checks.
    }
    @Test void admissionRetainsInvalidFlagsAndEraDependentAnnotations() {
        var hash = new HashData(0x54, "01".repeat(32));
        byte[] transfer = encodeCanonical(lock(), new Transfer("TOKEN", 100000000, hash, 123));
        var before = (Transfer) RvnHistoricalAssetCodec.decodeSpendable(transfer, true, false).orElseThrow().payload();
        assertNull(before.message()); assertEquals(0, before.expires());
        var after = (Transfer) RvnHistoricalAssetCodec.decodeSpendable(transfer, true, true).orElseThrow().payload();
        assertEquals(hash, after.message()); assertEquals(123, after.expires());
        byte[] issue = encodeCanonical(lock(), new Issue("TOKEN", 100000000, 0, true, null));
        issue[issue.length - 3] = 2;
        assertThrows(SecurityException.class, () -> RvnHistoricalAssetCodec.decodeSpendable(issue, true, true));
        issue[issue.length - 3] = 1; issue[issue.length - 2] = 1;
        assertThrows(SecurityException.class, () -> RvnHistoricalAssetCodec.decodeSpendable(issue, true, true));
    }

    @Test void nullFormsUseCoreFixedOffsetsAndDoNotValidateFlagsAtDecodeTime() {
        var out = new ByteArrayOutputStream(); out.write(0xc0); out.write(0x14); out.writeBytes(new byte[20]);
        out.write(8); out.write(5); out.writeBytes("#KYC1".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); out.write(2);
        var address = RvnHistoricalAssetCodec.decodeNull(out.toByteArray()).orElseThrow();
        assertEquals("#KYC1", address.name()); assertEquals(2, address.flag()); assertEquals("00".repeat(20), address.addressHash());
        byte[] global = {(byte)0xc0,0x50,0x50,7,4,'$','A','B','C',1};
        assertEquals("$ABC", RvnHistoricalAssetCodec.decodeNull(global).orElseThrow().name());
        byte[] verifier = {(byte)0xc0,0x50,5,4,'t','r','u','e'};
        assertEquals("true", RvnHistoricalAssetCodec.decodeNull(verifier).orElseThrow().verifier());
        assertTrue(RvnHistoricalAssetCodec.decodeNull(new byte[]{(byte)0xc0}).isEmpty());
    }
}
