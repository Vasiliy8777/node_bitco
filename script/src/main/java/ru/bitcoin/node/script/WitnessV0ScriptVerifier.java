package ru.bitcoin.node.script;

import ru.bitcoin.node.crypto.hash.Sha256;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.Witness;

import java.util.Arrays;

public final class WitnessV0ScriptVerifier {

    private WitnessV0ScriptVerifier() {
    }

    public static boolean verifyP2wpkh(
            Transaction transaction,
            int inputIndex,
            byte[] scriptSig,
            Witness witness,
            WitnessProgram witnessProgram,
            long amount,
            int flags
    ) {
        if (transaction == null) {
            throw new IllegalArgumentException(
                    "transaction must not be null"
            );
        }

        if (inputIndex < 0
                || inputIndex >= transaction.inputs().size()) {

            throw new IllegalArgumentException(
                    "inputIndex is out of range: "
                            + inputIndex
            );
        }

        if (scriptSig == null) {
            throw new IllegalArgumentException(
                    "scriptSig must not be null"
            );
        }

        if (witness == null) {
            throw new IllegalArgumentException(
                    "witness must not be null"
            );
        }

        if (witnessProgram == null) {
            throw new IllegalArgumentException(
                    "witnessProgram must not be null"
            );
        }

        if (amount < 0) {
            throw new IllegalArgumentException(
                    "amount must not be negative"
            );
        }
        if (!ScriptVerifyFlags.has(
                flags,
                ScriptVerifyFlags.WITNESS
        )) {
            return false;
        }
        /*
         * Эта функция обслуживает только
         * witness v0 key-hash:
         *
         * OP_0 <20-byte program>
         */
        if (!witnessProgram.isP2wpkh()) {
            return false;
        }

        /*
         * Native witness program requires
         * completely empty scriptSig.
         */
        if (scriptSig.length != 0) {
            return false;
        }

        /*
         * BIP141 P2WPKH:
         *
         * witness = [signature, publicKey]
         */
        if (witness.size() != 2) {
            return false;
        }

        byte[] signature =
                witness.item(0);

        byte[] publicKey =
                witness.item(1);

        /*
         * Equivalent scriptCode:
         *
         * OP_DUP
         * OP_HASH160
         * PUSH20 <pubKeyHash>
         * OP_EQUALVERIFY
         * OP_CHECKSIG
         */
        byte[] scriptCode =
                createP2wpkhScriptCode(
                        witnessProgram.program()
                );

        ScriptMachine machine =
                new ScriptMachine();

        /*
         * Witness items become the initial execution stack
         * in their serialized order.
         */
        machine.push(signature);
        machine.push(publicKey);

        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        transaction,
                        inputIndex,
                        scriptCode,
                        flags,
                        amount,
                        SignatureVersion.WITNESS_V0
                );

        try {

            ScriptInterpreter.execute(
                    scriptCode,
                    machine,
                    context
            );

        } catch (ScriptExecutionException
                 | ScriptParseException e) {

            return false;
        }

        /*
         * Witness v0 has implicit CLEANSTACK semantics.
         *
         * Successful witness execution must leave
         * exactly one true item.
         */
        if (machine.size() != 1) {
            return false;
        }

        return ScriptNumber.castToBool(
                machine.peek()
        );
    }

    public static byte[] createP2wpkhScriptCode(
            byte[] publicKeyHash
    ) {
        if (publicKeyHash == null) {
            throw new IllegalArgumentException(
                    "publicKeyHash must not be null"
            );
        }

        if (publicKeyHash.length != 20) {
            throw new IllegalArgumentException(
                    "P2WPKH public-key hash must be 20 bytes"
            );
        }

        byte[] script =
                new byte[25];

        int offset = 0;

        script[offset++] =
                (byte) Opcode.OP_DUP;

        script[offset++] =
                (byte) Opcode.OP_HASH160;

        /*
         * Direct push of exactly 20 bytes.
         */
        script[offset++] =
                20;

        System.arraycopy(
                publicKeyHash,
                0,
                script,
                offset,
                publicKeyHash.length
        );

        offset += publicKeyHash.length;

        script[offset++] =
                (byte) Opcode.OP_EQUALVERIFY;

        script[offset] =
                (byte) Opcode.OP_CHECKSIG;

        return script;
    }
    public static boolean verifyP2wsh(
            Transaction transaction,
            int inputIndex,
            byte[] scriptSig,
            Witness witness,
            WitnessProgram witnessProgram,
            long amount,
            int flags
    ) {
        if (transaction == null) {
            throw new IllegalArgumentException(
                    "transaction must not be null"
            );
        }

        if (inputIndex < 0
                || inputIndex >= transaction.inputs().size()) {

            throw new IllegalArgumentException(
                    "inputIndex is out of range: "
                            + inputIndex
            );
        }

        if (scriptSig == null) {
            throw new IllegalArgumentException(
                    "scriptSig must not be null"
            );
        }

        if (witness == null) {
            throw new IllegalArgumentException(
                    "witness must not be null"
            );
        }

        if (witnessProgram == null) {
            throw new IllegalArgumentException(
                    "witnessProgram must not be null"
            );
        }

        if (amount < 0) {
            throw new IllegalArgumentException(
                    "amount must not be negative"
            );
        }

        if (!ScriptVerifyFlags.has(
                flags,
                ScriptVerifyFlags.WITNESS
        )) {
            return false;
        }

        if (!witnessProgram.isP2wsh()) {
            return false;
        }

        /*
         * Native witness program:
         * scriptSig must be exactly empty.
         */
        if (scriptSig.length != 0) {
            return false;
        }

        /*
         * Last witness item is witnessScript.
         *
         * Therefore witness cannot be empty.
         */
        if (witness.isEmpty()) {
            return false;
        }

        byte[] witnessScript =
                witness.item(
                        witness.size() - 1
                );

        /*
         * BIP141:
         * witnessScript may be up to 10,000 bytes.
         */
        if (witnessScript.length > 10_000) {
            return false;
        }

        /*
         * SHA256(witnessScript) must equal
         * the 32-byte witness program.
         */
        byte[] scriptHash =
                Sha256.hash(
                        witnessScript
                );

        if (!Arrays.equals(
                scriptHash,
                witnessProgram.program()
        )) {
            return false;
        }

        ScriptMachine machine =
                new ScriptMachine();

        /*
         * All witness items except the final
         * witnessScript form the initial stack.
         *
         * Each of those elements is limited
         * to 520 bytes under witness v0 rules.
         */
        for (int i = 0;
             i < witness.size() - 1;
             i++) {

            byte[] item =
                    witness.item(i);

            if (item.length > 520) {
                return false;
            }

            machine.push(
                    item
            );
        }

        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        transaction,
                        inputIndex,
                        witnessScript,
                        flags,
                        amount,
                        SignatureVersion.WITNESS_V0
                );

        try {

            ScriptInterpreter.execute(
                    witnessScript,
                    machine,
                    context
            );

        } catch (ScriptExecutionException
                 | ScriptParseException e) {

            return false;
        }

        /*
         * Witness v0 has implicit CLEANSTACK:
         *
         * exactly one true item must remain.
         */
        if (machine.size() != 1) {
            return false;
        }

        return ScriptNumber.castToBool(
                machine.peek()
        );
    }
}