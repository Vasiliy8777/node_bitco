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
        return verifyP2wpkh(transaction, inputIndex, scriptSig, witness, witnessProgram, amount, flags, ScriptDialect.BITCOIN);
    }
    public static boolean verifyP2wpkh(Transaction transaction, int inputIndex, byte[] scriptSig, Witness witness,
                                      WitnessProgram witnessProgram, long amount, int flags, ScriptDialect dialect) {
        return verifyP2wpkh(transaction, inputIndex, scriptSig, witness, witnessProgram, amount, flags, dialect, false);
    }
    public static boolean verifyP2wpkh(Transaction transaction, int inputIndex, byte[] scriptSig, Witness witness,
                                      WitnessProgram witnessProgram, long amount, int flags, boolean detailed) {
        return verifyP2wpkh(transaction, inputIndex, scriptSig, witness, witnessProgram, amount, flags, ScriptDialect.BITCOIN, detailed);
    }
    private static boolean verifyP2wpkh(Transaction transaction, int inputIndex, byte[] scriptSig, Witness witness,
                                       WitnessProgram witnessProgram, long amount, int flags, ScriptDialect dialect, boolean detailed) {
        return verifyP2wpkh(transaction,inputIndex,scriptSig,witness,witnessProgram,amount,flags,dialect,detailed,SignatureCheckMode.VERIFY);
    }
    public static boolean verifyP2wpkh(Transaction transaction,int inputIndex,byte[] scriptSig,Witness witness,
                               WitnessProgram witnessProgram,long amount,int flags,ScriptDialect dialect,boolean detailed,SignatureCheckMode mode) {
        java.util.Objects.requireNonNull(dialect);
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
            return failure(detailed, "Witness requires empty scriptSig");
        }

        /*
         * BIP141 P2WPKH:
         *
         * witness = [signature, publicKey]
         */
        if (witness.size() != 2) {
            return failure(detailed, "Witness program hash mismatch");
        }
        if (witness.item(0).length > 520 || witness.item(1).length > 520) {
            return failure(detailed, "Push value size limit exceeded");
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
                ).withDialect(dialect).withSignatureCheckMode(mode);

        try {

            ScriptInterpreter.execute(
                    scriptCode,
                    machine,
                    context
            );

        } catch (ScriptExecutionException
                 | ScriptParseException e) {
            if (detailed) throw e;
            return false;
        }

        /*
         * Witness v0 has implicit CLEANSTACK semantics.
         *
         * Successful witness execution must leave
         * exactly one true item.
         */
        if (machine.size() != 1) {
            return failure(detailed, "Stack size must be exactly one after execution");
        }

        return ScriptNumber.castToBool(machine.peek()) || failure(detailed, "Script evaluated without error but finished with a false/empty top stack element");
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
        return verifyP2wsh(transaction, inputIndex, scriptSig, witness, witnessProgram, amount, flags, ScriptDialect.BITCOIN);
    }
    public static boolean verifyP2wsh(Transaction transaction, int inputIndex, byte[] scriptSig, Witness witness,
                                     WitnessProgram witnessProgram, long amount, int flags, ScriptDialect dialect) {
        return verifyP2wsh(transaction, inputIndex, scriptSig, witness, witnessProgram, amount, flags, dialect, false);
    }
    public static boolean verifyP2wsh(Transaction transaction, int inputIndex, byte[] scriptSig, Witness witness,
                                     WitnessProgram witnessProgram, long amount, int flags, boolean detailed) {
        return verifyP2wsh(transaction, inputIndex, scriptSig, witness, witnessProgram, amount, flags, ScriptDialect.BITCOIN, detailed);
    }
    private static boolean verifyP2wsh(Transaction transaction, int inputIndex, byte[] scriptSig, Witness witness,
                                      WitnessProgram witnessProgram, long amount, int flags, ScriptDialect dialect, boolean detailed) {
        return verifyP2wsh(transaction,inputIndex,scriptSig,witness,witnessProgram,amount,flags,dialect,detailed,SignatureCheckMode.VERIFY);
    }
    public static boolean verifyP2wsh(Transaction transaction,int inputIndex,byte[] scriptSig,Witness witness,
                               WitnessProgram witnessProgram,long amount,int flags,ScriptDialect dialect,boolean detailed,SignatureCheckMode mode) {
        java.util.Objects.requireNonNull(dialect);
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
            return failure(detailed, "Witness requires empty scriptSig");
        }

        /*
         * Last witness item is witnessScript.
         *
         * Therefore witness cannot be empty.
         */
        if (witness.isEmpty()) {
            return failure(detailed, "Witness program was passed an empty witness");
        }

        byte[] witnessScript =
                witness.item(
                        witness.size() - 1
                );

        /*
         * BIP141:
         * witnessScript may be up to 10,000 bytes.
         */

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
            return failure(detailed, "Witness program hash mismatch");
        }
        if (witnessScript.length > 10_000) {
            return failure(detailed, "Script is too big");
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
                return failure(detailed, "Push value size limit exceeded");
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
                ).withDialect(dialect).withSignatureCheckMode(mode);

        try {

            ScriptInterpreter.execute(
                    witnessScript,
                    machine,
                    context
            );

        } catch (ScriptExecutionException
                 | ScriptParseException e) {
            if (detailed) throw e;
            return false;
        }

        /*
         * Witness v0 has implicit CLEANSTACK:
         *
         * exactly one true item must remain.
         */
        if (machine.size() != 1) {
            return failure(detailed, "Stack size must be exactly one after execution");
        }

        return ScriptNumber.castToBool(machine.peek()) || failure(detailed, "Script evaluated without error but finished with a false/empty top stack element");
    }

    private static boolean failure(boolean detailed, String error) {
        if (detailed) throw new ScriptVerificationException(error);
        return false;
    }
}
