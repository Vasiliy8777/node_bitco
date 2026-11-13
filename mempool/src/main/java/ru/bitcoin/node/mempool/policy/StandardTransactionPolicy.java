package ru.bitcoin.node.mempool.policy;

import ru.bitcoin.node.consensus.transaction.*;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.*;
import java.util.*;

/** Local relay policy. Never invoke from block consensus validation. */
public final class StandardTransactionPolicy {
    private StandardTransactionPolicy() { }
    public static void validateStructure(Transaction tx, int maxDataCarrierBytes) { validateStructure(tx, maxDataCarrierBytes, 3000); }
    public static void validateStructure(Transaction tx, int maxDataCarrierBytes, long dustRate) {
        for (var input : tx.inputs()) {
            validateScriptSig(input.scriptSig());
        }
        int remaining = maxDataCarrierBytes;
        int dust = 0;
        for (var output : tx.outputs()) {
            byte[] script = output.scriptPubKey();
            var type = ScriptPubKeyClassifier.classify(script);
            if (type == ScriptPubKeyType.NONSTANDARD) fail("scriptpubkey");
            if (type == ScriptPubKeyType.MULTISIG) {
                var ops = ScriptParser.parse(script);
                if (ops.get(ops.size() - 2).opcode() - Opcode.OP_1 + 1 > 3) throw new MempoolPolicyException("bare-multisig-size", "scriptpubkey", null);
            }
            if (type == ScriptPubKeyType.NULL_DATA) {
                if (!pushOnly(Arrays.copyOfRange(script, 1, script.length))) throw new MempoolPolicyException("datacarrier-script", "scriptpubkey", null);
                remaining -= script.length;
                if (remaining < 0) throw new MempoolPolicyException("datacarrier-size", "datacarrier", null);
            }
            if (output.value() < dustThreshold(output, dustRate)) dust++;
        }
        if (dust > 1) fail("dust");
    }
    public static void validateScriptSig(byte[] script) {
        if (!pushOnly(script)) fail("scriptsig-not-pushonly");
    }

    public static long dustThreshold(TxOut output) { return dustThreshold(output, 3000); }
    public static long dustThreshold(TxOut output, long dustRate) {
        byte[] script = output.scriptPubKey();
        if (script.length > 10_000 || (script.length > 0 && script[0] == 0x6a)) return 0;
        long serializedSize = 8L + (script.length < 253 ? 1 : script.length <= 65535 ? 3 : 5) + script.length;
        return new FeeRate(dustRate).feeForVSize(serializedSize + (WitnessProgram.parse(script).isPresent() ? 67 : 148));
    }

    /** Ephemeral dust is only allowed with zero fee; package relay must also enforce its spend. */
    public static void validateDustFee(Transaction tx, long fee) { validateDustFee(tx, fee, 3000); }
    public static void validateDustFee(Transaction tx, long fee, long dustRate) {
        validateDustFee(tx, fee, fee, dustRate);
    }
    public static void validateDustFee(Transaction tx, long baseFee, long modifiedFee, long dustRate) {
        if ((baseFee != 0 || modifiedFee != 0)
                && tx.outputs().stream().anyMatch(out -> out.value() < dustThreshold(out, dustRate))) fail("dust-with-fee");
    }

    public static long validateInputs(Transaction tx, UtxoView view) {
        return validateInputs(tx, view, true);
    }
    public static long validateInputs(Transaction tx, UtxoView view, boolean checkSigops) {
        long legacySigops = 0;
        for (var input : tx.inputs()) {
            byte[] script = view.find(input.previousOutput()).orElseThrow().scriptPubKey();
            legacySigops += SigOpCounter.count(input.scriptSig(), true);
            if (P2shScript.isPayToScriptHash(script)) {
                var redeem = SigOpCounter.lastPush(input.scriptSig());
                if (redeem != null) legacySigops += SigOpCounter.count(redeem, true);
            } else legacySigops += SigOpCounter.count(script, true);
            if (legacySigops > 2500) inputFail("legacy-sigops", "non-witness sigops exceed bip54 limit");
        }
        var scripts = new byte[tx.inputs().size()][];
        var wrappedInputs = new boolean[tx.inputs().size()];
        // ValidateInputsStandardness completes for all inputs before IsWitnessStandard.
        for (int index = 0; index < tx.inputs().size(); index++) {
            var input = tx.inputs().get(index);
            byte[] script = view.find(input.previousOutput()).orElseThrow().scriptPubKey();
            var type = ScriptPubKeyClassifier.classify(script);
            if (type == ScriptPubKeyType.NONSTANDARD) inputFail("nonstandard-input", "input " + index + " script unknown");
            if (type == ScriptPubKeyType.WITNESS_UNKNOWN) inputFail("nonstandard-input", "input " + index + " witness program is undefined");
            boolean wrapped = type == ScriptPubKeyType.SCRIPTHASH;
            if (wrapped) {
                var stack = new ScriptMachine();
                try { ScriptInterpreter.execute(input.scriptSig(), stack, new ScriptExecutionContext(tx, index, input.scriptSig(), ScriptVerifyFlags.NONE)); }
                catch (ScriptExecutionException error) { inputFail("malformed-redeemscript", "p2sh scriptsig malformed (input " + index + ": " + error.error() + ")"); }
                catch (ScriptParseException error) { inputFail("malformed-redeemscript", "p2sh scriptsig malformed (input " + index + ": " + error.error() + ")"); }
                if (stack.isEmpty()) inputFail("missing-redeemscript", "input " + index + " P2SH redeemscript missing");
                script = stack.pop();
                long sigops = SigOpCounter.count(script, true);
                if (sigops > 15) inputFail("p2sh-sigops", "p2sh redeemscript sigops exceed limit (input " + index + ": " + sigops + " > 15)");
            }
            scripts[index] = script; wrappedInputs[index] = wrapped;
        }
        for (int index = 0; index < tx.inputs().size(); index++) {
            var input = tx.inputs().get(index); byte[] script = scripts[index]; boolean wrapped = wrappedInputs[index];
            Witness witness = input.witness();
            if (witness.isEmpty()) continue;
            if (ScriptPubKeyClassifier.classify(script) == ScriptPubKeyType.ANCHOR && !wrapped) witnessFail("anchor-witness");
            var program = WitnessProgram.parse(script);
            if (program.isEmpty()) witnessFail("unexpected-witness");
            var wp = program.orElseThrow();
            if (wp.isP2wsh()) {
                int count = witness.size() - 1;
                if (witness.item(count).length > 3600 || count > 100) witnessFail("p2wsh-limits");
                for (int i = 0; i < count; i++) if (witness.item(i).length > 80) witnessFail("p2wsh-item-size");
            }
            if (!wrapped && wp.version() == 1 && wp.programLength() == 32) {
                byte[] last = witness.item(witness.size() - 1);
                if (witness.size() >= 2 && last.length > 0 && last[0] == 0x50) witnessFail("taproot-annex");
                if (witness.size() >= 2) {
                    if (last.length == 0) witnessFail("empty-control-block");
                    if ((last[0] & 0xfe) == 0xc0) {
                        for (int i = 0; i < witness.size() - 2; i++) if (witness.item(i).length > 80) witnessFail("tapscript-item-size");
                    }
                }
            }
        }
        long sigops = TransactionSigOpCost.calculate(tx, view, StandardScriptVerifyFlags.STANDARD);
        if (checkSigops && sigops > 16_000) throw new MempoolPolicyException("too-many-sigops", "bad-txns-too-many-sigops", Long.toString(sigops));
        return sigops;
    }
    private static boolean pushOnly(byte[] script) {
        try { return P2shScript.isPushOnly(script); }
        catch (ScriptParseException e) { return false; }
    }
    private static void fail(String reason) { throw new MempoolAdmissionException(reason); }
    private static void inputFail(String diagnostic, String debug) { throw new MempoolPolicyException(diagnostic, "bad-txns-nonstandard-inputs", debug); }
    private static void witnessFail(String diagnostic) { throw new MempoolPolicyException(diagnostic, "bad-witness-nonstandard", null); }
}
