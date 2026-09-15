package ru.bitcoin.node.mempool.policy;

import ru.bitcoin.node.consensus.transaction.*;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.*;
import java.util.*;

/** Local relay policy. Never invoke from block consensus validation. */
public final class StandardTransactionPolicy {
    private StandardTransactionPolicy() { }
    public static void validateStructure(Transaction tx, int maxDataCarrierBytes) {
        for (var input : tx.inputs()) {
            if (!pushOnly(input.scriptSig())) fail("scriptsig-not-pushonly");
        }
        int remaining = maxDataCarrierBytes;
        int dust = 0;
        for (var output : tx.outputs()) {
            byte[] script = output.scriptPubKey();
            var type = ScriptPubKeyClassifier.classify(script);
            if (type == ScriptPubKeyType.NONSTANDARD) fail("scriptpubkey");
            if (type == ScriptPubKeyType.MULTISIG) {
                var ops = ScriptParser.parse(script);
                if (ops.get(ops.size() - 2).opcode() - Opcode.OP_1 + 1 > 3) fail("bare-multisig-size");
            }
            if (type == ScriptPubKeyType.NULL_DATA) {
                if (!pushOnly(Arrays.copyOfRange(script, 1, script.length))) fail("datacarrier-script");
                remaining -= script.length;
                if (remaining < 0) fail("datacarrier-size");
            }
            if (output.value() < dustThreshold(output)) dust++;
        }
        if (dust > 1) fail("dust");
    }

    public static long dustThreshold(TxOut output) {
        byte[] script = output.scriptPubKey();
        if (script.length > 10_000 || (script.length > 0 && script[0] == 0x6a)) return 0;
        long serializedSize = 8L + (script.length < 253 ? 1 : script.length <= 65535 ? 3 : 5) + script.length;
        return (serializedSize + (WitnessProgram.parse(script).isPresent() ? 67 : 148)) * 3;
    }

    /** Ephemeral dust is only allowed with zero fee; package relay must also enforce its spend. */
    public static void validateDustFee(Transaction tx, long fee) {
        if (fee != 0 && tx.outputs().stream().anyMatch(out -> out.value() < dustThreshold(out))) fail("dust-with-fee");
    }

    public static long validateInputs(Transaction tx, UtxoView view) {
        for (var input : tx.inputs()) {
            byte[] script = view.find(input.previousOutput()).orElseThrow().scriptPubKey();
            var type = ScriptPubKeyClassifier.classify(script);
            if (type == ScriptPubKeyType.NONSTANDARD || type == ScriptPubKeyType.WITNESS_UNKNOWN) fail("nonstandard-input");
            boolean wrapped = type == ScriptPubKeyType.SCRIPTHASH;
            if (wrapped) {
                script = SigOpCounter.lastPush(input.scriptSig());
                if (script == null) fail("missing-redeemscript");
                if (SigOpCounter.count(script, true) > 15) fail("p2sh-sigops");
            }
            Witness witness = input.witness();
            if (type == ScriptPubKeyType.ANCHOR && !witness.isEmpty()) fail("anchor-witness");
            if (witness.isEmpty()) continue;
            var program = WitnessProgram.parse(script);
            if (program.isEmpty()) fail("unexpected-witness");
            var wp = program.orElseThrow();
            if (wp.isP2wsh()) {
                int count = witness.size() - 1;
                if (witness.item(count).length > 3600 || count > 100) fail("p2wsh-limits");
                for (int i = 0; i < count; i++) if (witness.item(i).length > 80) fail("p2wsh-item-size");
            }
            if (!wrapped && wp.version() == 1 && wp.programLength() == 32) {
                byte[] last = witness.item(witness.size() - 1);
                if (witness.size() >= 2 && last.length > 0 && last[0] == 0x50) fail("taproot-annex");
                if (witness.size() >= 2) {
                    if (last.length == 0) fail("empty-control-block");
                    if ((last[0] & 0xfe) == 0xc0) {
                        for (int i = 0; i < witness.size() - 2; i++) if (witness.item(i).length > 80) fail("tapscript-item-size");
                    }
                }
            }
        }
        long sigops = TransactionSigOpCost.calculate(tx, view, StandardScriptVerifyFlags.STANDARD);
        if (sigops > 16_000) fail("too-many-sigops");
        return sigops;
    }
    private static boolean pushOnly(byte[] script) {
        try { return P2shScript.isPushOnly(script); }
        catch (ScriptParseException e) { return false; }
    }
    private static void fail(String reason) { throw new MempoolAdmissionException(reason); }
}
