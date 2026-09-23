package ru.bitcoin.node.stratum;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.stratum.protocol.VersionRolling;
import ru.bitcoin.node.stratum.protocol.StratumException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class VersionRollingTest {
    @Test void intersectsMasksAndReplacesOnlyNegotiatedBits() {
        var rolling = new VersionRolling();
        assertEquals(Map.of("version-rolling", true, "version-rolling.mask", "1fffe000"),
                rolling.configure(Map.of("version-rolling.min-bit-count", 2)));
        assertEquals(0x20000002, rolling.version(0x3fffe002, "00000000"));
        assertEquals(0x3fffe002, rolling.version(0x20000002, "1FFFE000"));
        assertEquals(20, assertThrows(StratumException.class, () -> rolling.version(0x20000002, "20000000")).code());
        assertThrows(StratumException.class, () -> rolling.version(0x20000002, "00000002"));
        assertThrows(IllegalArgumentException.class, () -> rolling.version(0x20000002, "1fffe00g"));
        assertEquals(true, rolling.configure(Map.of("version-rolling.min-bit-count", 16)).get("version-rolling"));
        assertInstanceOf(String.class, rolling.configure(Map.of("version-rolling.mask", "00006000", "version-rolling.min-bit-count", 2)).get("version-rolling"));
        assertEquals(0x3fffe000, rolling.version(0x20000000, "1fffe000"));
    }

    @Test void invalidNegotiationDoesNotEnableOrChangeTheExtension() {
        var rolling = new VersionRolling();
        for (var parameters : java.util.List.of(Map.of(), Map.of("version-rolling.min-bit-count", -1),
                Map.of("version-rolling.min-bit-count", 2.5), Map.of("version-rolling.min-bit-count", "2"),
                Map.of("version-rolling.min-bit-count", 17),
                Map.of("version-rolling.mask", "00000001", "version-rolling.min-bit-count", 1),
                Map.of("version-rolling.mask", "zzzzzzzz", "version-rolling.min-bit-count", 0))) {
            assertInstanceOf(String.class, rolling.configure(parameters).get("version-rolling"));
            assertFalse(rolling.enabled());
        }
        assertThrows(StratumException.class, () -> rolling.version(0x20000000, "00000000"));
        assertEquals(Map.of("version-rolling", true, "version-rolling.mask", "00006000"),
                rolling.configure(Map.of("version-rolling.mask", "e0006001", "version-rolling.min-bit-count", 2)));
        assertInstanceOf(String.class, rolling.configure(Map.of("version-rolling.min-bit-count", 99)).get("version-rolling"));
        assertEquals(0x20006001, rolling.version(0x20000001, "00006000"));
    }
}
