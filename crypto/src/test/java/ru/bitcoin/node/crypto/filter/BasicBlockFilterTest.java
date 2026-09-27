package ru.bitcoin.node.crypto.filter;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class BasicBlockFilterTest {
    @Test
    void matchesBip158TestnetGenesisVectorByteForByte() {
        var hex = HexFormat.of();
        Hash256 blockHash = Hash256.fromDisplayHex(
                "000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943");
        byte[] script = hex.parseHex(
                "4104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61d" +
                        "eb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac");
        assertArrayEquals(hex.parseHex("019dfca8"), BasicBlockFilter.encode(blockHash, List.of(script)));
    }

    @Test
    void emptyFilterIsSingleZeroCompactSize() {
        assertArrayEquals(new byte[]{0}, BasicBlockFilter.encode(new Hash256(new byte[32]), List.of()));
    }
}
