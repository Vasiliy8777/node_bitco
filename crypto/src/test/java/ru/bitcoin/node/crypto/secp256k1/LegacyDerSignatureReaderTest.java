package ru.bitcoin.node.crypto.secp256k1;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.HexUtils;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

class LegacyDerSignatureReaderTest {
    @Test void toleratesHistoricalLengthsPaddingAndTrailingBytes() {
        for (String hex : new String[]{"3006020101020101", "30060201010201010000", "308101020101020101",
                "3080028102000102810101", "30080202000102020001"}) {
            var signature = LegacyDerSignatureReader.parse(HexUtils.decode(hex));
            assertEquals(BigInteger.ONE, signature.r());
            assertEquals(BigInteger.ONE, signature.s());
        }
    }
    @Test void treatsIntegerBytesAsUnsigned() {
        assertEquals(BigInteger.valueOf(128), LegacyDerSignatureReader.parse(HexUtils.decode("3006020180020101")).r());
    }
    @Test void rejectsInvalidScalarsAndTruncation() {
        for (String hex : new String[]{"", "3006020100020101", "3006020101020100", "3082", "300602ffffffff7f", "3006022101"}) {
            assertThrows(IllegalArgumentException.class, () -> LegacyDerSignatureReader.parse(HexUtils.decode(hex)));
        }
    }
}
