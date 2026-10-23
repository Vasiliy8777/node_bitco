package ru.bitcoin.node.rvn.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.rvn.protocol.*;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RvnBlockTemplateBuilderTest {
    private static final long HEIGHT = 4_200_000;
    private static final long TIME = 1_800_000_000;
    private static final long BITS = 0x1b012345;
    private final RvnPayoutAddress payout = RvnPayoutAddress.parse(
            RvnPayoutAndCoinbaseTest.encoded(60, new byte[20]), RvnNetwork.MAINNET);

    private RvnBlock build(long extraNonce, List<Transaction> transactions, long fees) {
        return RvnBlockTemplateBuilder.build(0x20000000, new byte[32], HEIGHT, TIME, BITS,
                extraNonce, payout, transactions, fees, true);
    }

    private Transaction spend(boolean witness) {
        return new Transaction(2, List.of(new TxIn(new OutPoint(new Hash256(new byte[32]), new UInt32(0)),
                new byte[0], TxIn.FINAL_SEQUENCE,
                witness ? new Witness(List.of(new byte[]{1, 2, 3})) : Witness.EMPTY)),
                List.of(new TxOut(100, new byte[]{0x51})), new UInt32(0));
    }

    @Test
    void emptyTemplatePaysSubsidyAndSerializesKawpowHeader() throws Exception {
        var block = build(0, List.of(), 0);
        var header = block.header();
        assertEquals(120, header.serialize().length);
        assertEquals(HEIGHT, header.declaredHeight());
        assertEquals(TIME, header.timestamp());
        assertEquals(BITS, header.compactBits());
        assertEquals(0, header.nonce64Bits());
        var coinbase = block.transactions().getFirst();
        assertFalse(coinbase.hasWitness());
        assertEquals(1250 * RvnBlockBodyChecks.COIN, coinbase.outputs().getFirst().value());
        assertArrayEquals(payout.scriptPubKey(), coinbase.outputs().getFirst().scriptPubKey());
        assertArrayEquals(coinbase.txId().bytes(), header.merkleRootWire());
        var decoded = RvnBlockCodec.parse(RvnBlockCodec.serialize(block));
        assertEquals(block.transactions(), decoded.transactions());
        assertArrayEquals(header.serialize(), decoded.header().serialize());
        RvnBlockBodyChecks.requireValid(decoded, true);
    }

    @Test
    void witnessCommitmentRoundtripAndTamperRejection() throws Exception {
        var tx = spend(true);
        var block = build(4, List.of(tx), 42);
        var coinbase = block.transactions().getFirst();
        assertEquals(1250 * RvnBlockBodyChecks.COIN + 42, coinbase.outputs().getFirst().value());
        assertEquals(2, coinbase.outputs().size());
        assertArrayEquals(new byte[32], coinbase.inputs().getFirst().witness().item(0));
        // Two leaves: zero coinbase wtxid and the selected transaction's wtxid.
        byte[] leaves = new byte[64];
        System.arraycopy(tx.wtxId().bytes(), 0, leaves, 32, 32);
        byte[] preimage = new byte[64];
        System.arraycopy(RvnWire.doubleSha(leaves), 0, preimage, 0, 32);
        assertArrayEquals(RvnWire.doubleSha(preimage),
                Arrays.copyOfRange(coinbase.outputs().getLast().scriptPubKey(), 6, 38));
        RvnBlockBodyChecks.requireValid(RvnBlockCodec.parse(RvnBlockCodec.serialize(block)), true);

        var input = tx.inputs().getFirst();
        var tampered = new Transaction(tx.version(), List.of(new TxIn(input.previousOutput(), input.scriptSig(),
                input.sequence(), new Witness(List.of(new byte[]{9})))), tx.outputs(), tx.lockTime());
        assertEquals(tx.txId(), tampered.txId());
        var failure = assertThrows(SecurityException.class, () -> RvnBlockBodyChecks.requireValid(
                new RvnBlock(block.header(), List.of(coinbase, tampered)), true));
        assertTrue(failure.getMessage().contains("witness commitment"));
    }

    @Test
    void extraNonceChangesJobAndWorkerProofPreservesInput() {
        var template = build(1, List.of(spend(false)), 1);
        assertFalse(template.transactions().getFirst().hasWitness());
        assertFalse(Arrays.equals(template.header().kawpowInputDigest(),
                build(2, List.of(spend(false)), 1).header().kawpowInputDigest()));
        byte[] mix = new byte[32]; Arrays.fill(mix, (byte) 0xa5);
        var solved = RvnBlockTemplateBuilder.withProof(template, Long.MIN_VALUE + 123, mix);
        assertEquals(Long.MIN_VALUE + 123, solved.header().nonce64Bits());
        assertArrayEquals(mix, solved.header().mixHashWire());
        mix[0] = 0;
        assertEquals((byte) 0xa5, solved.header().mixHashWire()[0]);
        assertArrayEquals(template.header().kawpowInputDigest(), solved.header().kawpowInputDigest());
        assertEquals(template.transactions(), solved.transactions());
        assertEquals(0, template.header().nonce64Bits());
    }

    @Test
    void rejectsInvalidSelectionsAndTruncatedHeaderFields() {
        assertThrows(IllegalArgumentException.class, () -> build(0, List.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> build(0,
                List.of(RvnCoinbase.create(HEIGHT, 0, 0, payout)), 0));
        var tx = spend(false);
        assertThrows(SecurityException.class, () -> build(0, List.of(tx, tx), 0));
        for (long invalid : new long[]{-1, 0x1_0000_0000L}) {
            assertThrows(IllegalArgumentException.class, () -> RvnBlockTemplateBuilder.build(1,
                    new byte[32], invalid, TIME, BITS, 0, payout, List.of(), 0, true));
            assertThrows(IllegalArgumentException.class, () -> RvnBlockTemplateBuilder.build(1,
                    new byte[32], HEIGHT, invalid, BITS, 0, payout, List.of(), 0, true));
            assertThrows(IllegalArgumentException.class, () -> RvnBlockTemplateBuilder.build(1,
                    new byte[32], HEIGHT, TIME, invalid, 0, payout, List.of(), 0, true));
        }
        assertThrows(IllegalArgumentException.class, () -> RvnBlockTemplateBuilder.withProof(
                build(0, List.of(), 0), 0, new byte[31]));
    }
}
