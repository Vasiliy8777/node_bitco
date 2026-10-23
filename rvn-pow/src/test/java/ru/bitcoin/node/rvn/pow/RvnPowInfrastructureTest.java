package ru.bitcoin.node.rvn.pow;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RvnPowInfrastructureTest {
    @Test
    void dispatchBoundaries() {
        var s = new RvnPowSchedule(100, 200);
        assertEquals(RvnPowAlgorithm.X16R, s.atHeight(99));
        assertEquals(RvnPowAlgorithm.X16RV2, s.atHeight(100));
        assertEquals(RvnPowAlgorithm.X16RV2, s.atHeight(199));
        assertEquals(RvnPowAlgorithm.KAWPOW, s.atHeight(200));
        assertThrows(IllegalArgumentException.class, () -> new RvnPowSchedule(100, 100));
    }

    @Test
    void noSilentFallback() {
        var router = new RvnPowRouter(new RvnPowSchedule(100, 200), new RvnPowUnavailable());
        assertThrows(RvnPowException.class, () -> router.calculate(1, new byte[80]));
        assertThrows(IllegalArgumentException.class, () -> router.calculate(200, new byte[80]));
        assertThrows(RvnPowException.class, () -> router.calculate(200, new byte[120]));
        byte[] raw = new byte[120];
        raw[76] = 1;
        assertThrows(RvnPowException.class, () -> router.calculate(200, raw));
    }

    @Test
    void defensiveCopies() {
        byte[] raw = new byte[80], id = new byte[32], pow = new byte[32];
        var vector = new RvnPowVector("test", RvnPowAlgorithm.X16R, raw, id, pow);
        raw[0] = 9;
        id[0] = 9;
        pow[0] = 9;
        assertEquals(0, vector.raw()[0]);
        vector.raw()[0] = 4;
        assertEquals(0, vector.raw()[0]);
        var result = new RvnPowResult(id, pow);
        id[0] = 1;
        assertEquals(9, result.blockIdWire()[0]);
        result.blockIdWire()[0] = 2;
        assertEquals(9, result.blockIdWire()[0]);
    }

    @Test
    void vectorSelfTestRequiresAllThreeAlgorithms() {
        var engine = new RvnPowEngine() {
            public RvnPowResult calculate(RvnPowAlgorithm a, byte[] raw) {
                byte[] id = new byte[32], pow = new byte[32];
                id[0] = (byte) (a.ordinal() + 1);
                return new RvnPowResult(id, pow);
            }
        };
        var vectors = new ArrayList<RvnPowVector>();
        for (var a : RvnPowAlgorithm.values()) {
            byte[] id = new byte[32];
            id[0] = (byte) (a.ordinal() + 1);
            vectors.add(new RvnPowVector(a.name(), a,
                    new byte[a == RvnPowAlgorithm.KAWPOW ? 120 : 80], id, new byte[32]));
        }
        assertDoesNotThrow(() -> new RvnPowValidatedEngine(engine, vectors));
        assertThrows(RvnPowException.class, () -> new RvnPowValidatedEngine(engine, vectors.subList(0, 2)));
        byte[] bad = new byte[32];
        bad[0] = 100;
        vectors.set(0, new RvnPowVector("bad", RvnPowAlgorithm.X16R, new byte[80], bad, new byte[32]));
        assertThrows(RvnPowException.class, () -> new RvnPowValidatedEngine(engine, vectors));
    }
}
