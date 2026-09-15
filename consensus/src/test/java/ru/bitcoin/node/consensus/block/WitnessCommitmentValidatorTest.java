package ru.bitcoin.node.consensus.block;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.HexUtils;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WitnessCommitmentValidatorTest {
    private static final byte[] ZERO = new byte[32];
    private static final byte[] PREFIX = HexUtils.decode("6a24aa21a9ed");

    @Test void allowsLegacyBlockWithoutCommitment() {
        var block = block(List.of(new TxOut(1, new byte[]{0x51})), List.of(), null);
        assertDoesNotThrow(() -> WitnessCommitmentValidator.validate(block, true));
        assertDoesNotThrow(() -> WitnessCommitmentValidator.validate(block, false));
    }
    @Test void validatesCoinbaseOnlyCommitmentAgainstKnownDoubleHash() {
        byte[] digest = HexUtils.decode("e2f61c3f71d1defd3fa999dfa36953755c690689799962b48bebd836974e8cf9");
        var block = block(List.of(output(digest)), List.of(ZERO), null);
        assertDoesNotThrow(() -> WitnessCommitmentValidator.validate(block, true));
    }
    @Test void rejectsMissingCommitmentWithWitness() {
        assertThrows(BlockValidationException.class, () -> WitnessCommitmentValidator.validate(
                block(List.of(new TxOut(1, new byte[]{0x51})), List.of(ZERO), null), true));
    }
    @Test void rejectsWitnessBeforeActivation() {
        assertThrows(BlockValidationException.class, () -> WitnessCommitmentValidator.validate(
                block(List.of(output(commitment(null))), List.of(ZERO), null), false));
    }
    @Test void requiresExactlyOne32ByteReservedValue() {
        for (List<byte[]> witness : List.of(List.<byte[]>of(), List.of(new byte[31]), List.of(ZERO, ZERO))) {
            assertThrows(BlockValidationException.class, () -> WitnessCommitmentValidator.validate(
                    block(List.of(output(commitment(null))), witness, null), true));
        }
    }
    @Test void lastMatchingCommitmentWinsAndSuffixIsAllowed() {
        byte[] good = output(commitment(null)).scriptPubKey();
        var extended = new TxOut(0, Arrays.copyOf(good, good.length + 10));
        assertDoesNotThrow(() -> WitnessCommitmentValidator.validate(
                block(List.of(output(ZERO), extended), List.of(ZERO), null), true));
        assertThrows(BlockValidationException.class, () -> WitnessCommitmentValidator.validate(
                block(List.of(extended, output(ZERO)), List.of(ZERO), null), true));
    }
    @Test void commitsToWtxidAndRejectsWitnessMutation() {
        var tx = spending((byte) 1);
        var outputs = List.of(output(commitment(tx)));
        assertDoesNotThrow(() -> WitnessCommitmentValidator.validate(block(outputs, List.of(ZERO), tx), true));
        assertEquals(tx.txId(), spending((byte) 2).txId());
        assertThrows(BlockValidationException.class, () -> WitnessCommitmentValidator.validate(
                block(outputs, List.of(ZERO), spending((byte) 2)), true));
    }
    private static Transaction spending(byte value) {
        return new Transaction(2, List.of(new TxIn(new OutPoint(new Hash256(new byte[]{
                1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0}), new UInt32(0)),
                new byte[0], TxIn.FINAL_SEQUENCE, new Witness(List.of(new byte[]{value})))),
                List.of(new TxOut(1, new byte[]{0x51})), new UInt32(0));
    }
    private static byte[] commitment(Transaction tx) {
        byte[] preimage = new byte[64];
        if (tx != null) System.arraycopy(MerkleTree.calculateRoot(List.of(new Hash256(ZERO), tx.wtxId())).bytes(), 0, preimage, 0, 32);
        return Hash256Digest.hash(preimage).bytes();
    }
    private static TxOut output(byte[] hash) {
        byte[] script = Arrays.copyOf(PREFIX, 38);
        System.arraycopy(hash, 0, script, 6, 32);
        return new TxOut(0, script);
    }
    private static Block block(List<TxOut> outputs, List<byte[]> witness, Transaction tx) {
        var coinbase = new Transaction(1, List.of(new TxIn(OutPoint.coinbase(), new byte[]{0x51,0},
                TxIn.FINAL_SEQUENCE, new Witness(witness))), outputs, new UInt32(0));
        var txs = tx == null ? List.of(coinbase) : List.of(coinbase, tx);
        return new Block(new BlockHeader(4, new Hash256(ZERO),
                MerkleTree.calculateRoot(txs.stream().map(Transaction::txId).toList()),
                new UInt32(1), new UInt32(0x207fffff), new UInt32(0)), txs);
    }
}
