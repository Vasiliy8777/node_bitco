package ru.bitcoin.node.script;

import ru.bitcoin.node.crypto.SchnorrSignature;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.Arrays;
import java.util.List;

/** Mutable signature budget, scoped to one tapscript execution. */
public final class TaprootExecutionData {
    private final Transaction tx;
    private final int input;
    private final List<TxOut> coins;
    private final byte[] annex;
    private final byte[] leaf;
    private final boolean spentOutputsReady;
    private long budget;
    private final SignatureCheckMode mode;

    public TaprootExecutionData(Transaction tx, int input, List<TxOut> coins, byte[] annex, byte[] leaf, long budget) {
        this(tx,input,coins,annex,leaf,budget,SignatureCheckMode.VERIFY);
    }
    public TaprootExecutionData(Transaction tx,int input,List<TxOut> coins,byte[] annex,byte[] leaf,long budget,SignatureCheckMode mode) {
        this.mode = java.util.Objects.requireNonNull(mode);
        this.tx = tx; this.input = input;
        this.coins = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(coins));
        this.spentOutputsReady = mode != SignatureCheckMode.VERIFY_WITHOUT_PRECOMPUTED && coins.stream().allMatch(java.util.Objects::nonNull);
        this.annex = annex == null ? null : annex.clone(); this.leaf = leaf == null ? null : leaf.clone(); this.budget = budget;
    }

    public boolean check(byte[] signature, byte[] key, long codeSeparator, int flags) {
        if (key.length == 0) throw new ScriptExecutionException(ScriptError.TAPSCRIPT_EMPTY_PUBKEY, "Empty tapscript public key");
        if (signature.length != 0 && (budget -= 50) < 0) throw new ScriptExecutionException(ScriptError.TAPSCRIPT_VALIDATION_WEIGHT, "Tapscript signature budget exhausted");
        if (key.length != 32) {
            if (ScriptVerifyFlags.has(flags, ScriptVerifyFlags.DISCOURAGE_UPGRADABLE_PUBKEYTYPE)) {
                throw new ScriptExecutionException(ScriptError.DISCOURAGE_UPGRADABLE_PUBKEYTYPE, "Discouraged tapscript key type");
            }
            return signature.length != 0;
        }
        if (signature.length == 0) return false;
        verify(signature, key, codeSeparator);
        return true;
    }

    public void verify(byte[] signature, byte[] key, long codeSeparator) {
        if (mode == SignatureCheckMode.ESTIMATE) {
            if (signature.length == 0) throw new ScriptExecutionException(ScriptError.SCHNORR_SIG,"Empty estimated signature");
            return;
        }
        if (signature.length != 64 && signature.length != 65) throw new ScriptExecutionException(ScriptError.SCHNORR_SIG_SIZE, "Invalid Schnorr signature size");
        int type = signature.length == 64 ? 0 : signature[64] & 0xff;
        if (signature.length == 65 && type == 0) throw new ScriptExecutionException(ScriptError.SCHNORR_SIG_HASHTYPE, "Explicit SIGHASH_DEFAULT is invalid");
        if (!spentOutputsReady) throw new ScriptExecutionException(ScriptError.SCHNORR_SIG_HASHTYPE, "Missing Taproot spent outputs");
        byte[] hash = TaprootSignatureHash.calculate(tx, input, coins, type, annex, leaf, codeSeparator);
        if (!SchnorrSignature.verify(hash, key, Arrays.copyOf(signature, 64))) {
            throw new ScriptExecutionException(ScriptError.SCHNORR_SIG, "Invalid Schnorr signature");
        }
    }
}
