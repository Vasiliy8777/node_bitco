package ru.bitcoin.node.mempool.policy;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.transaction.*;
import ru.bitcoin.node.crypto.hash.Sha256;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StandardTransactionPolicyTest {
    private static final byte[] P2WPKH = HexFormat.of().parseHex("0014" + "11".repeat(20));
    private static final OutPoint POINT = new OutPoint(Hash256.fromDisplayHex("11".repeat(32)), new UInt32(0));
    @Test void rejectsNonPushScriptSigAndNonstandardOutput() {
        assertThrows(MempoolAdmissionException.class, () -> StandardTransactionPolicy.validateStructure(
                tx(new byte[]{0x51,0x75}, Witness.EMPTY, List.of(new TxOut(1000, P2WPKH))), 100_000));
        assertThrows(MempoolAdmissionException.class, () -> StandardTransactionPolicy.validateStructure(
                tx(new byte[0], Witness.EMPTY, List.of(new TxOut(1000, new byte[]{0x51}))), 100_000));
    }
    @Test void datacarrierUsesTotalBudgetAndPushOnlyPayload() {
        var tx = tx(new byte[0], Witness.EMPTY, List.of(new TxOut(0, new byte[]{0x6a,0}), new TxOut(0, new byte[]{0x6a,0})));
        assertDoesNotThrow(() -> StandardTransactionPolicy.validateStructure(tx, 4));
        assertThrows(MempoolAdmissionException.class, () -> StandardTransactionPolicy.validateStructure(tx, 3));
        assertThrows(MempoolAdmissionException.class, () -> StandardTransactionPolicy.validateStructure(
                tx(new byte[0], Witness.EMPTY, List.of(new TxOut(0, new byte[]{0x6a,0x75}))), 100_000));
    }
    @Test void dustThresholdsAndEphemeralFeeRule() {
        assertEquals(294, StandardTransactionPolicy.dustThreshold(new TxOut(1, P2WPKH)));
        byte[] p2pkh = HexFormat.of().parseHex("76a914" + "11".repeat(20) + "88ac");
        assertEquals(546, StandardTransactionPolicy.dustThreshold(new TxOut(1, p2pkh)));
        assertEquals(0, StandardTransactionPolicy.dustThreshold(new TxOut(0, new byte[]{0x6a})));
        var dust = tx(new byte[0], Witness.EMPTY, List.of(new TxOut(293, P2WPKH)));
        assertThrows(MempoolAdmissionException.class, () -> StandardTransactionPolicy.validateDustFee(dust, 1));
        assertDoesNotThrow(() -> StandardTransactionPolicy.validateDustFee(dust, 0));
        assertDoesNotThrow(() -> StandardTransactionPolicy.validateDustFee(
                tx(new byte[0], Witness.EMPTY, List.of(new TxOut(294, P2WPKH))), 1));
        assertThrows(MempoolAdmissionException.class, () -> StandardTransactionPolicy.validateStructure(
                tx(new byte[0], Witness.EMPTY, List.of(new TxOut(1, P2WPKH), new TxOut(1, P2WPKH))), 100_000));
    }
    @Test void p2wshPolicyBoundaries() {
        assertDoesNotThrow(() -> checkWsh(100,80,3600));
        assertThrows(MempoolAdmissionException.class, () -> checkWsh(101,80,3600));
        assertThrows(MempoolAdmissionException.class, () -> checkWsh(100,81,3600));
        assertThrows(MempoolAdmissionException.class, () -> checkWsh(100,80,3601));
    }
    @Test void annexAndTapscriptStackItemsAreNonstandard() {
        byte[] taproot = HexFormat.of().parseHex("5120" + "11".repeat(32));
        assertThrows(MempoolAdmissionException.class, () -> checkWitness(taproot, List.of(new byte[64], new byte[]{0x50})));
        byte[] control = new byte[33]; control[0] = (byte)0xc0;
        assertDoesNotThrow(() -> checkWitness(taproot, List.of(new byte[80], new byte[]{0x51}, control)));
        assertThrows(MempoolAdmissionException.class, () -> checkWitness(taproot, List.of(new byte[81], new byte[]{0x51}, control)));
    }
    @Test void nativeAnchorMustHaveEmptyWitness() {
        byte[] anchor = HexFormat.of().parseHex("51024e73");
        assertDoesNotThrow(() -> checkWitness(anchor, List.of()));
        assertThrows(MempoolAdmissionException.class, () -> checkWitness(anchor, List.of(new byte[0])));
    }
    @Test void p2shSigopsAndTransactionSigopsLimits() {
        byte[] p2sh = HexFormat.of().parseHex("a914" + "11".repeat(20) + "87");
        byte[] redeem = new byte[16]; Arrays.fill(redeem,(byte)0xac);
        byte[] sig = new byte[17]; sig[0]=16; System.arraycopy(redeem,0,sig,1,16);
        assertThrows(MempoolAdmissionException.class, () -> StandardTransactionPolicy.validateInputs(tx(sig,Witness.EMPTY,List.of(new TxOut(1000,P2WPKH))), view(p2sh)));
        byte[] script = new byte[4001]; Arrays.fill(script,(byte)0xac);
        assertThrows(MempoolAdmissionException.class, () -> StandardTransactionPolicy.validateInputs(
                tx(new byte[0],Witness.EMPTY,List.of(new TxOut(1000,script))),view(P2WPKH)));
    }
    private static void checkWsh(int items,int size,int scriptSize) {
        byte[] script=new byte[scriptSize];
        List<byte[]> stack=new ArrayList<>();
        for(int i=0;i<items;i++) stack.add(new byte[size]);
        stack.add(script);
        checkWitness(HexFormat.of().parseHex("0020"+HexFormat.of().formatHex(Sha256.hash(script))),stack);
    }
    private static void checkWitness(byte[] script,List<byte[]> witness) {
        StandardTransactionPolicy.validateInputs(tx(new byte[0],new Witness(witness),List.of(new TxOut(1000,P2WPKH))),view(script));
    }
    private static UtxoView view(byte[] script) { return out -> Optional.of(new UtxoEntry(2000,script,1,false)); }
    private static Transaction tx(byte[] script,Witness witness,List<TxOut> outputs) {
        return new Transaction(2,List.of(new TxIn(POINT,script,TxIn.FINAL_SEQUENCE,witness)),outputs,new UInt32(0));
    }
}
