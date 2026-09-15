package ru.bitcoin.node.script.validation;

import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.crypto.SchnorrSignature;
import ru.bitcoin.node.crypto.hash.TaggedHash;
import ru.bitcoin.node.crypto.secp256k1.Secp256k1;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.*;
import java.math.BigInteger;
import java.util.*;

public final class TaprootValidator {
    private TaprootValidator() { }

    public static void validate(Transaction tx, int input, List<TxOut> coins, byte[] outputKey, int flags) {
        Witness witness = tx.inputs().get(input).witness();
        int size = witness.size();
        if (size == 0) throw new ScriptExecutionException("Empty Taproot witness");
        byte[] annex = null;
        byte[] last = witness.item(size - 1);
        if (size >= 2 && last.length > 0 && last[0] == 0x50) {
            annex = last;
            size--;
        }
        if (size == 1) {
            new TaprootExecutionData(tx, input, coins, annex, null, 0).verify(witness.item(0), outputKey, 0xffff_ffffL);
            return;
        }
        byte[] control = witness.item(size - 1);
        byte[] script = witness.item(size - 2);
        if (control.length < 33 || control.length > 33 + 32 * 128 || (control.length - 33) % 32 != 0) {
            throw new ScriptExecutionException("Invalid Taproot control block size");
        }
        int leafVersion = control[0] & 0xfe;
        byte[] leaf = TaggedHash.hash("TapLeaf", new byte[]{(byte) leafVersion}, CompactSize.encode(script.length), script);
        byte[] root = leaf;
        for (int offset = 33; offset < control.length; offset += 32) {
            byte[] node = Arrays.copyOfRange(control, offset, offset + 32);
            root = Arrays.compareUnsigned(root, node) < 0
                    ? TaggedHash.hash("TapBranch", root, node) : TaggedHash.hash("TapBranch", node, root);
        }
        byte[] internalKey = Arrays.copyOfRange(control, 1, 33);
        BigInteger tweak = new BigInteger(1, TaggedHash.hash("TapTweak", internalKey, root));
        if (tweak.compareTo(Secp256k1.N) >= 0) throw new ScriptExecutionException("Taproot tweak out of range");
        try {
            var point = SchnorrSignature.liftX(internalKey).add(Secp256k1.DOMAIN.getG().multiply(tweak)).normalize();
            if (point.isInfinity() || !point.getAffineXCoord().toBigInteger().equals(new BigInteger(1, outputKey))
                    || point.getAffineYCoord().toBigInteger().testBit(0) != ((control[0] & 1) != 0)) {
                throw new ScriptExecutionException("Taproot control block does not commit to output key");
            }
        } catch (IllegalArgumentException e) {
            throw new ScriptExecutionException("Invalid Taproot internal key");
        }
        if (leafVersion != 0xc0) {
            if (ScriptVerifyFlags.has(flags, ScriptVerifyFlags.DISCOURAGE_UPGRADABLE_TAPROOT_VERSION)) {
                throw new ScriptExecutionException("Discouraged Taproot leaf version");
            }
            return;
        }
        if (hasOpSuccess(script)) {
            if (ScriptVerifyFlags.has(flags, ScriptVerifyFlags.DISCOURAGE_OP_SUCCESS)) {
                throw new ScriptExecutionException("Discouraged OP_SUCCESS");
            }
            return;
        }
        var machine = new ScriptMachine();
        for (int i = 0; i < size - 2; i++) {
            byte[] item = witness.item(i);
            if (item.length > 520) throw new ScriptExecutionException("Tapscript initial element exceeds 520 bytes");
            machine.push(item);
        }
        machine.validateStackSize();
        long budget = 50L + CompactSize.encode(witness.size()).length;
        for (int i = 0; i < witness.size(); i++) {
            int length = witness.item(i).length;
            budget += CompactSize.encode(length).length + (long) length;
        }
        var data = new TaprootExecutionData(tx, input, coins, annex, leaf, budget);
        ScriptInterpreter.execute(script, machine, new ScriptExecutionContext(tx, input, script, flags,
                coins.get(input).value(), SignatureVersion.TAPSCRIPT, data));
        if (machine.size() != 1 || !ScriptNumber.castToBool(machine.peek())) {
            throw new ScriptExecutionException("Tapscript must finish with exactly one true element");
        }
    }

    private static boolean hasOpSuccess(byte[] script) {
        for (int pos = 0; pos < script.length;) {
            int opcode = script[pos++] & 0xff;
            if (opcode == 80 || opcode == 98 || opcode >= 126 && opcode <= 129
                    || opcode >= 131 && opcode <= 134 || opcode >= 137 && opcode <= 138
                    || opcode >= 141 && opcode <= 142 || opcode >= 149 && opcode <= 153
                    || opcode >= 187 && opcode <= 254) return true;
            long length = opcode <= 75 ? opcode : 0;
            if (opcode >= 76 && opcode <= 78) {
                int bytes = 1 << (opcode - 76);
                if (script.length - pos < bytes) throw new ScriptExecutionException("Truncated tapscript push length");
                for (int i = 0; i < bytes; i++) length |= (script[pos++] & 0xffL) << (8 * i);
            }
            if (length > script.length - pos) throw new ScriptExecutionException("Truncated tapscript push");
            pos += (int) length;
        }
        return false;
    }
}
