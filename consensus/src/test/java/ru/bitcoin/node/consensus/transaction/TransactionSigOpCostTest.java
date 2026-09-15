package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.HexUtils;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.ScriptVerifyFlags;
import ru.bitcoin.node.consensus.block.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TransactionSigOpCostTest {
    private static final int FLAGS = ScriptVerifyFlags.P2SH | ScriptVerifyFlags.WITNESS;
    @Test void countsP2shRedeemScriptAccurately() {
        var tx = tx(HexUtils.decode("000252ae"), List.of());
        assertEquals(8, TransactionSigOpCost.calculate(tx, view("a914" + "11".repeat(20) + "87"), FLAGS));
        assertEquals(0, TransactionSigOpCost.calculate(tx, view("a914" + "11".repeat(20) + "87"), 0));
    }
    @Test void countsNativeAndWrappedWitness() {
        assertEquals(1, TransactionSigOpCost.calculate(tx(new byte[0], List.of()), view("0014" + "11".repeat(20)), FLAGS));
        assertEquals(2, TransactionSigOpCost.calculate(tx(new byte[0], List.of(HexUtils.decode("52ae"))), view("0020" + "11".repeat(32)), FLAGS));
        assertEquals(1, TransactionSigOpCost.calculate(tx(HexUtils.decode("160014" + "11".repeat(20)), List.of()),
                view("a914" + "11".repeat(20) + "87"), FLAGS));
    }
    @Test void unknownWitnessVersionsDoNotCountAsWitnessV0() {
        assertEquals(0, TransactionSigOpCost.calculate(tx(new byte[0], List.of(HexUtils.decode("ac"))),
                view("5120" + "11".repeat(32)), FLAGS));
    }
    @Test void enforcesExactBlockBoundary() {
        assertDoesNotThrow(() -> BlockSigOpsValidator.validate(80_000));
        assertThrows(BlockValidationException.class, () -> BlockSigOpsValidator.validate(80_001));
    }
    private static UtxoView view(String script) {
        return point -> Optional.of(new UtxoEntry(1000, HexUtils.decode(script), 1, false));
    }
    private static Transaction tx(byte[] scriptSig, List<byte[]> witness) {
        return new Transaction(2, List.of(new TxIn(new OutPoint(Hash256.fromDisplayHex("11".repeat(32)), new UInt32(0)),
                scriptSig, TxIn.FINAL_SEQUENCE, new Witness(witness))), List.of(new TxOut(1, new byte[]{0x51})), new UInt32(0));
    }
}
