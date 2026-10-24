package ru.bitcoin.node.rvn.chain;

import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.script.*;

/** RVN adapter for shared legacy and witness-v0 primitives. Activation flags must
 * come from the candidate's RVN branch. Includes RVN suffix-consuming execution
 * and signature serialization; asset state/authorization are separate checks.
 * Bitcoin Taproot/anchor shortcuts and policy flags are intentionally unavailable.
 */
public final class RvnNativeScriptChecks {
    private RvnNativeScriptChecks() {}
    private static final int SUPPORTED = ScriptVerifyFlags.P2SH | ScriptVerifyFlags.DERSIG
            | ScriptVerifyFlags.CHECKLOCKTIMEVERIFY | ScriptVerifyFlags.CHECKSEQUENCEVERIFY
            | ScriptVerifyFlags.WITNESS | ScriptVerifyFlags.NULLDUMMY;

    static void requireFlags(int flags) {
        if ((flags & ~SUPPORTED) != 0) throw new IllegalArgumentException("Unsupported RVN script flags");
        if (ScriptVerifyFlags.has(flags, ScriptVerifyFlags.WITNESS)
                && !ScriptVerifyFlags.has(flags, ScriptVerifyFlags.P2SH))
            throw new IllegalArgumentException("RVN witness verification requires P2SH");
    }

    public static void requireValid(Transaction tx, int index, RvnCoin coin, int flags) {
        requireFlags(flags);
        if (tx.isCoinbase()) throw new IllegalArgumentException("Coinbase has no input script to verify");
        var input = tx.inputs().get(index);
        byte[] script = coin.output().scriptPubKey();
        byte[] sig = input.scriptSig();
        if (ScriptVerifyFlags.has(flags, ScriptVerifyFlags.WITNESS)) {
            var nativeProgram = WitnessProgram.parse(script);
            if (nativeProgram.isPresent()) {
                if (sig.length != 0 || !LegacyScriptVerifier.verify(tx, index, sig, script, flags, ScriptDialect.RAVENCOIN)) fail();
                witness(tx, index, coin, nativeProgram.get(), flags);
                return;
            }
            if (P2shScript.isPayToScriptHash(script)) {
                byte[] redeem = SigOpCounter.lastPush(sig);
                var program = redeem == null ? java.util.Optional.<WitnessProgram>empty() : WitnessProgram.parse(redeem);
                if (program.isPresent()) {
                    if (P2shWitnessProgram.extract(sig).isEmpty()
                            || !ScriptNumber.castToBool(program.get().program())
                            || !LegacyScriptVerifier.verifyP2shOuter(tx, index, sig, script, flags, ScriptDialect.RAVENCOIN)) fail();
                    witness(tx, index, coin, program.get(), flags);
                    return;
                }
            }
            if (!input.witness().isEmpty()) fail();
        }
        if (!LegacyScriptVerifier.verify(tx, index, sig, script, flags, ScriptDialect.RAVENCOIN)) fail();
    }

    private static void witness(Transaction tx, int index, RvnCoin coin, WitnessProgram program, int flags) {
        // RVN has no Taproot execution here: versions 1..16 retain upgrade semantics.
        if (program.version() != 0) return;
        var witness = tx.inputs().get(index).witness();
        boolean valid = program.isP2wpkh()
                ? WitnessV0ScriptVerifier.verifyP2wpkh(tx, index, new byte[0], witness, program, coin.output().value(), flags, ScriptDialect.RAVENCOIN)
                : program.isP2wsh() && WitnessV0ScriptVerifier.verifyP2wsh(tx, index, new byte[0], witness, program, coin.output().value(), flags, ScriptDialect.RAVENCOIN);
        if (!valid) fail();
    }

    private static void fail() { throw new SecurityException("RVN input script verification failed"); }
}
