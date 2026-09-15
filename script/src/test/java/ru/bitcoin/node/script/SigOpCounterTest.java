package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.HexUtils;
import static org.junit.jupiter.api.Assertions.*;

class SigOpCounterTest {
    @Test void countsAccurateAndLegacyMultisig() {
        byte[] script = HexUtils.decode("52aeacadaf");
        assertEquals(24, SigOpCounter.count(script, true));
        assertEquals(42, SigOpCounter.count(script, false));
    }
    @Test void ignoresPushedBytesButCountsNonExecutedBranches() {
        assertEquals(1, SigOpCounter.count(HexUtils.decode("01ac0063ac68"), false));
    }
    @Test void preservesCountBeforeTruncatedPush() {
        assertEquals(1, SigOpCounter.count(HexUtils.decode("ac4eFFFFFFFF"), false));
        assertEquals(1, SigOpCounter.count(HexUtils.decode("ac4c02ac"), false));
    }
    @Test void extractsLastPushOnlyValue() {
        assertArrayEquals(new byte[]{0x52, (byte) 0xae}, SigOpCounter.lastPush(HexUtils.decode("000252ae")));
        assertNull(SigOpCounter.lastPush(HexUtils.decode("760252ae")));
        assertNull(SigOpCounter.lastPush(HexUtils.decode("4c02ac")));
    }
}
