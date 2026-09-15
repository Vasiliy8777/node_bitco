package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScriptVerifyFlagNamesTest {
    @Test void mapsEveryDeclaredFlagWithoutDependingOnCoreBitPositions() throws Exception {
        int seen = 0;
        for (var field : ScriptVerifyFlags.class.getFields()) {
            if (field.getType() != int.class) continue;
            int value = field.getInt(null);
            assertEquals(value, ScriptVerifyFlags.parseNames(field.getName()), field.getName());
            if (value != 0) {
                assertEquals(1, Integer.bitCount(value));
                assertEquals(0, seen & value, "Overlapping flag: " + field.getName());
                seen |= value;
            }
        }
    }
    @Test void combinesSymbolicListsAndAllowsNoFlags() {
        assertEquals(0, ScriptVerifyFlags.parseNames(""));
        assertEquals(0, ScriptVerifyFlags.parseNames("NONE"));
        assertEquals(ScriptVerifyFlags.P2SH | ScriptVerifyFlags.WITNESS | ScriptVerifyFlags.TAPROOT,
                ScriptVerifyFlags.parseNames("P2SH, WITNESS,TAPROOT,P2SH"));
    }
    @Test void rejectsUnknownFlagsAndMalformedLists() {
        for (String value : new String[]{"UNKNOWN", "P2SH,UNKNOWN", "P2SH,", ",P2SH", "P2SH,,WITNESS", "1", "p2sh"}) {
            assertThrows(IllegalArgumentException.class, () -> ScriptVerifyFlags.parseNames(value), value);
        }
        assertThrows(NullPointerException.class, () -> ScriptVerifyFlags.parseNames(null));
    }
}
