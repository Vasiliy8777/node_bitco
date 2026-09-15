package ru.bitcoin.node.protocol.serialization;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.HexUtils;
import static org.junit.jupiter.api.Assertions.*;

class ParserLimitsTest {
    @Test void rejectsHugeInputCountFromTinyPayload() {
        assertThrows(IllegalArgumentException.class, () -> TransactionParser.parse(HexUtils.decode("01000000fefeffff7f")));
    }
    @Test void rejectsHugeBlockTransactionCountFromTinyPayload() {
        assertThrows(IllegalArgumentException.class, () -> BlockParser.parse(HexUtils.decode("00".repeat(80) + "fefeffff7f")));
    }
    @Test void rejectsSuperfluousWitnessEncoding() {
        String prefix = "01000000000101" + "00".repeat(32) + "ffffffff020101ffffffff0101000000000000000151";
        assertThrows(IllegalArgumentException.class, () -> TransactionParser.parse(HexUtils.decode(prefix + "0000000000")));
        // A stack with one empty item is nonempty and is not a superfluous record.
        assertTrue(TransactionParser.parse(HexUtils.decode(prefix + "010000000000")).hasWitness());
    }
    @Test void rejectsHugeWitnessCountBeforeAllocation() {
        String prefix = "01000000000101" + "00".repeat(32) + "ffffffff020101ffffffff0101000000000000000151";
        assertThrows(IllegalArgumentException.class, () -> TransactionParser.parse(HexUtils.decode(prefix + "fefeffff7f00000000")));
    }
}
