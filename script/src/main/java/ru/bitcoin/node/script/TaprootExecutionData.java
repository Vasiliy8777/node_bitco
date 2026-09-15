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
    private long budget;

    public TaprootExecutionData(Transaction tx, int input, List<TxOut> coins, byte[] annex, byte[] leaf, long budget) {
        this.tx = tx; this.input = input; this.coins = List.copyOf(coins);
        this.annex = annex == null ? null : annex.clone(); this.leaf = leaf == null ? null : leaf.clone(); this.budget = budget;
    }

    public boolean check(byte[] signature, byte[] key, long codeSeparator, int flags) {
        if (key.length == 0) throw new ScriptExecutionException("Empty tapscript public key");
        if (signature.length != 0 && (budget -= 50) < 0) throw new ScriptExecutionException("Tapscript signature budget exhausted");
        if (key.length != 32) {
            if (ScriptVerifyFlags.has(flags, ScriptVerifyFlags.DISCOURAGE_UPGRADABLE_PUBKEYTYPE)) {
                throw new ScriptExecutionException("Discouraged tapscript key type");
            }
            return signature.length != 0;
        }
        if (signature.length == 0) return false;
        verify(signature, key, codeSeparator);
        return true;
    }

    public void verify(byte[] signature, byte[] key, long codeSeparator) {
        if (signature.length != 64 && signature.length != 65) throw new ScriptExecutionException("Invalid Schnorr signature size");
        int type = signature.length == 64 ? 0 : signature[64] & 0xff;
        if (signature.length == 65 && type == 0) throw new ScriptExecutionException("Explicit SIGHASH_DEFAULT is invalid");
        byte[] hash = TaprootSignatureHash.calculate(tx, input, coins, type, annex, leaf, codeSeparator);
        if (!SchnorrSignature.verify(hash, key, Arrays.copyOf(signature, 64))) {
            throw new ScriptExecutionException("Invalid Schnorr signature");
        }
    }
}
