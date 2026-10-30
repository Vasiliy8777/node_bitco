package ru.bitcoin.node.rvn.app.mining;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.rvn.chain.*;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RvnRealTargetGpuBenchmarkTest {
    @Test void expectationIncludesEqualityAtTarget() {
        assertEquals(1.0, RvnRealTargetGpuBenchmark.expectedHashes(BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)));
        assertEquals(2.0, RvnRealTargetGpuBenchmark.expectedHashes(BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE)));
        assertThrows(IllegalArgumentException.class, () -> RvnRealTargetGpuBenchmark.expectedHashes(BigInteger.ZERO));
    }

    @Test void newWorkKeepsRealTransactionsWitnessTargetHeightAndTimeButNotKnownProof() throws Exception {
        var spend = new Transaction(2, List.of(new TxIn(new OutPoint(new Hash256(new byte[32]), new UInt32(0)),
                new byte[0], TxIn.FINAL_SEQUENCE, new Witness(List.of(new byte[]{1, 2, 3})))),
                List.of(new TxOut(100, new byte[]{0x51})), new UInt32(0));
        var original = RvnBlockTemplateBuilder.build(0x30000000, new byte[32], 4_602_000,
                1_800_000_000, 0x1b012345L, 1,
                RvnPayoutAddress.parse("RXBurnXXXXXXXXXXXXXXXXXXXXXXWUo9FV", RvnNetwork.MAINNET), List.of(spend), 42, true);
        var changed = RvnRealTargetGpuBenchmark.freshTemplate(original, 987654321L);
        assertArrayEquals(original.header().previousHashWire(), changed.header().previousHashWire());
        assertEquals(original.header().declaredHeight(), changed.header().declaredHeight());
        assertEquals(original.header().timestamp(), changed.header().timestamp());
        assertEquals(original.header().compactBits(), changed.header().compactBits());
        assertSame(spend, changed.transactions().get(1));
        assertEquals(original.transactions().getFirst().outputs(), changed.transactions().getFirst().outputs());
        assertFalse(Arrays.equals(original.header().kawpowInputDigest(), changed.header().kawpowInputDigest()));
        assertArrayEquals(new byte[32], changed.header().mixHashWire());
        RvnBlockBodyChecks.requireValid(RvnBlockCodec.parse(RvnBlockCodec.serialize(changed)), true);
    }
}
