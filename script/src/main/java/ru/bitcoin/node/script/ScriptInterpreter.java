package ru.bitcoin.node.script;

import ru.bitcoin.node.crypto.hash.Hash160;
import ru.bitcoin.node.crypto.secp256k1.EcdsaSignature;
import ru.bitcoin.node.crypto.secp256k1.PublicKey;
import ru.bitcoin.node.crypto.secp256k1.Secp256k1;

import java.util.Arrays;
import java.util.List;

public final class ScriptInterpreter {
    private static final long LOCKTIME_THRESHOLD =
            500_000_000L;
    /*
     * BIP68 / BIP112 nSequence constants.
     *
     * Эти значения намеренно находятся здесь отдельно
     * от consensus.transaction.SequenceLocks:
     * module script не должен зависеть от consensus.
     */
    private static final long SEQUENCE_LOCKTIME_DISABLE_FLAG =
            1L << 31;

    private static final long SEQUENCE_LOCKTIME_TYPE_FLAG =
            1L << 22;

    private static final long SEQUENCE_LOCKTIME_MASK =
            0x0000ffffL;

    private ScriptInterpreter() {
    }

    public static void execute(
            byte[] script,
            ScriptMachine machine
    ) {
        execute(
                script,
                machine,
                null
        );
    }
    private static void executeCheckSequenceVerify(
            ScriptMachine machine,
            ScriptExecutionContext context
    ) {

        /*
         * До активации BIP112 opcode 0xb2 является OP_NOP3.
         */
        if (context == null
                || !ScriptVerifyFlags.has(
                context.flags(),
                ScriptVerifyFlags.CHECKSEQUENCEVERIFY
        )) {

            return;
        }

        machine.requireStackSize(1);

        /*
         * CSV, как и CLTV, operand НЕ удаляет.
         *
         * maxNumSize = 5 нужен, потому что operand
         * может содержать bit 31.
         */
        long requiredSequence =
                ScriptNumber.decode(
                        machine.peek(),
                        5
                );

        if (requiredSequence < 0) {

            throw new ScriptExecutionException(
                    "OP_CHECKSEQUENCEVERIFY requires non-negative sequence"
            );
        }

        /*
         * Если disable flag установлен в operand самого Script,
         * CSV трактуется как NOP.
         *
         * Это важная часть BIP112.
         */
        if ((requiredSequence
                & SEQUENCE_LOCKTIME_DISABLE_FLAG) != 0) {

            return;
        }

        /*
         * Relative lock-time semantics существуют
         * только для transaction version >= 2.
         */
        if (context.transaction().version() < 2) {

            throw new ScriptExecutionException(
                    "OP_CHECKSEQUENCEVERIFY requires transaction version >= 2"
            );
        }

        long transactionSequence =
                context.transaction()
                        .inputs()
                        .get(context.inputIndex())
                        .sequence()
                        .value();

        /*
         * Если bit 31 установлен в nSequence самого input,
         * BIP68 semantics для него отключены.
         */
        if ((transactionSequence
                & SEQUENCE_LOCKTIME_DISABLE_FLAG) != 0) {

            throw new ScriptExecutionException(
                    "OP_CHECKSEQUENCEVERIFY input sequence disables relative lock-time"
            );
        }

        /*
         * Height-based operand нельзя сравнивать
         * с time-based nSequence и наоборот.
         */
        long typeMask =
                SEQUENCE_LOCKTIME_TYPE_FLAG;

        if ((requiredSequence & typeMask)
                != (transactionSequence & typeMask)) {

            throw new ScriptExecutionException(
                    "OP_CHECKSEQUENCEVERIFY sequence type mismatch"
            );
        }

        /*
         * При сравнении учитываются только:
         *
         * bit 22       — height/time type
         * bits 0..15   — relative lock value
         *
         * Остальные биты не участвуют.
         */
        long comparisonMask =
                SEQUENCE_LOCKTIME_TYPE_FLAG
                        | SEQUENCE_LOCKTIME_MASK;

        long requiredMasked =
                requiredSequence
                        & comparisonMask;

        long transactionMasked =
                transactionSequence
                        & comparisonMask;

        if (requiredMasked
                > transactionMasked) {

            throw new ScriptExecutionException(
                    "OP_CHECKSEQUENCEVERIFY sequence requirement not satisfied"
            );
        }
    }
    private static void executeCheckLockTimeVerify(
            ScriptMachine machine,
            ScriptExecutionContext context
    ) {

        /*
         * До активации BIP65 opcode 0xb1 является OP_NOP2.
         */
        if (context == null
                || !ScriptVerifyFlags.has(
                context.flags(),
                ScriptVerifyFlags.CHECKLOCKTIMEVERIFY
        )) {

            return;
        }

        machine.requireStackSize(1);

        /*
         * CLTV только читает верхний элемент.
         * Он НЕ удаляет его со stack.
         */
        long requiredLockTime =
                ScriptNumber.decode(
                        machine.peek(),
                        5
                );

        if (requiredLockTime < 0) {

            throw new ScriptExecutionException(
                    "OP_CHECKLOCKTIMEVERIFY requires non-negative locktime"
            );
        }

        long transactionLockTime =
                context.transaction()
                        .lockTime()
                        .value();

        /*
         * Нельзя сравнивать block-height locktime
         * с timestamp locktime.
         */
        boolean requiredIsHeight =
                requiredLockTime
                        < LOCKTIME_THRESHOLD;

        boolean transactionIsHeight =
                transactionLockTime
                        < LOCKTIME_THRESHOLD;

        if (requiredIsHeight
                != transactionIsHeight) {

            throw new ScriptExecutionException(
                    "OP_CHECKLOCKTIMEVERIFY locktime type mismatch"
            );
        }

        /*
         * Скрипт требует:
         *
         * tx.nLockTime >= requiredLockTime
         */
        if (requiredLockTime
                > transactionLockTime) {

            throw new ScriptExecutionException(
                    "OP_CHECKLOCKTIMEVERIFY locktime requirement not satisfied"
            );
        }

        /*
         * nLockTime не действует на input с final sequence.
         *
         * Поэтому такой input не может удовлетворить CLTV.
         */
        long sequence =
                context.transaction()
                        .inputs()
                        .get(context.inputIndex())
                        .sequence()
                        .value();

        if (sequence
                == 0xffff_ffffL) {

            throw new ScriptExecutionException(
                    "OP_CHECKLOCKTIMEVERIFY requires non-final input sequence"
            );
        }
    }
    public static void execute(
            byte[] script,
            ScriptMachine machine,
            ScriptExecutionContext context
    ) {
        if (script == null) {
            throw new IllegalArgumentException(
                    "script must not be null"
            );
        }

        if (machine == null) {
            throw new IllegalArgumentException(
                    "machine must not be null"
            );
        }

        List<ScriptInstruction> instructions =
                ScriptParser.parse(script);

        /*
         * Byte offset непосредственно после последнего
         * выполненного OP_CODESEPARATOR.
         *
         * Если separator ещё не выполнялся,
         * scriptCode начинается с byte 0.
         */
        int lastCodeSeparatorOffset = 0;

        int nextInstructionOffset = 0;

        for (ScriptInstruction instruction
                : instructions) {

            int instructionLength =
                    serializedLength(
                            instruction
                    );

            nextInstructionOffset =
                    Math.addExact(
                            nextInstructionOffset,
                            instructionLength
                    );

            /*
             * OP_CODESEPARATOR сам ничего не кладёт
             * на stack.
             *
             * Он лишь меняет начало scriptCode
             * для последующих signature operations.
             */
            if (!instruction.isPushData()
                    && instruction.opcode()
                    == Opcode.OP_CODESEPARATOR) {

                lastCodeSeparatorOffset =
                        nextInstructionOffset;

                continue;
            }

            executeInstruction(
                    instruction,
                    machine,
                    context,
                    lastCodeSeparatorOffset
            );
        }

        if (nextInstructionOffset
                != script.length) {

            throw new ScriptExecutionException(
                    "Script instruction offsets do not match script length"
            );
        }
    }

    private static void executeInstruction(
            ScriptInstruction instruction,
            ScriptMachine machine,
            ScriptExecutionContext context,
            int lastCodeSeparatorOffset
    ) {

        if (instruction.isPushData()) {

            byte[] data =
                    instruction.data();

            machine.push(
                    data == null
                            ? new byte[0]
                            : data
            );

            return;
        }

        int opcode =
                instruction.opcode();

        if (opcode == Opcode.OP_1NEGATE) {

            machine.push(
                    ScriptNumber.encode(-1)
            );

            return;
        }

        if (Opcode.isSmallInteger(opcode)) {

            long value =
                    opcode
                            - Opcode.OP_1
                            + 1L;

            machine.push(
                    ScriptNumber.encode(value)
            );

            return;
        }

        switch (opcode) {
            case Opcode.OP_CHECKLOCKTIMEVERIFY ->
                    executeCheckLockTimeVerify(
                            machine,
                            context
                    );
            case Opcode.OP_CHECKSEQUENCEVERIFY ->
                    executeCheckSequenceVerify(
                            machine,
                            context
                    );

            case Opcode.OP_DUP ->
                    machine.duplicateTop();

            case Opcode.OP_DROP ->
                    machine.pop();

            case Opcode.OP_EQUAL ->
                    executeEqual(machine);

            case Opcode.OP_EQUALVERIFY -> {
                executeEqual(machine);
                executeVerify(machine);
            }

            case Opcode.OP_VERIFY ->
                    executeVerify(machine);

            case Opcode.OP_HASH160 ->
                    executeHash160(machine);

            case Opcode.OP_CHECKSIG ->
                    executeCheckSig(
                            machine,
                            context,
                            lastCodeSeparatorOffset
                    );

            case Opcode.OP_CHECKSIGVERIFY -> {

                executeCheckSig(
                        machine,
                        context,
                        lastCodeSeparatorOffset
                );

                executeVerify(machine);
            }

            default ->
                    throw new ScriptExecutionException(
                            "Unsupported opcode: 0x"
                                    + String.format(
                                    "%02x",
                                    opcode
                            )
                    );
        }
    }

    private static void executeEqual(
            ScriptMachine machine
    ) {
        machine.requireStackSize(2);

        byte[] first =
                machine.pop();

        byte[] second =
                machine.pop();

        machine.push(
                Arrays.equals(
                        first,
                        second
                )
                        ? ScriptNumber.encode(1)
                        : ScriptNumber.encode(0)
        );
    }

    private static void executeVerify(
            ScriptMachine machine
    ) {
        machine.requireStackSize(1);

        byte[] value =
                machine.pop();

        if (!ScriptNumber.castToBool(value)) {

            throw new ScriptExecutionException(
                    "OP_VERIFY failed"
            );
        }
    }

    private static void executeHash160(
            ScriptMachine machine
    ) {
        machine.requireStackSize(1);

        byte[] value =
                machine.pop();

        machine.push(
                Hash160.hash(value)
        );
    }

    private static void executeCheckSig(
            ScriptMachine machine,
            ScriptExecutionContext context,
            int lastCodeSeparatorOffset
    ) {
        if (context == null) {

            throw new ScriptExecutionException(
                    "OP_CHECKSIG requires transaction context"
            );
        }

        machine.requireStackSize(2);

        byte[] publicKeyBytes =
                machine.pop();

        byte[] signatureWithHashType =
                machine.pop();

        /*
         * Empty signature is simply false.
         */
        if (signatureWithHashType.length == 0) {

            machine.push(
                    ScriptNumber.encode(0)
            );

            return;
        }

        SignatureEncoding.validateSignature(
                signatureWithHashType,
                context.flags()
        );

        SignatureEncoding.validatePublicKey(
                publicKeyBytes,
                context.flags()
        );

        int hashType =
                Byte.toUnsignedInt(
                        signatureWithHashType[
                                signatureWithHashType.length - 1
                                ]
                );

        byte[] der =
                Arrays.copyOf(
                        signatureWithHashType,
                        signatureWithHashType.length - 1
                );

        boolean valid;

        try {

            /*
             * 1. scriptCode начинается после
             *    последнего выполненного CODESEPARATOR.
             */
            byte[] scriptCode =
                    LegacyScriptCode.afterCodeSeparator(
                            context.scriptCode(),
                            lastCodeSeparatorOffset
                    );

            /*
             * 2. Legacy FindAndDelete:
             *
             * удаляем из scriptCode точный serialized
             * push текущей подписи.
             */
            if (context.signatureVersion()
                    == SignatureVersion.LEGACY) {

                scriptCode =
                        LegacyScriptCode.findAndDeleteSignature(
                                scriptCode,
                                signatureWithHashType
                        );
            }

            /*
             * 3. LegacySignatureHash затем выполняет
             * остальные legacy sighash transformations,
             * включая удаление оставшихся
             * OP_CODESEPARATOR.
             */
            byte[] digest;

            if (context.signatureVersion()
                    == SignatureVersion.WITNESS_V0) {

                digest =
                        WitnessV0SignatureHash.calculate(
                                context.transaction(),
                                context.inputIndex(),
                                scriptCode,
                                context.amount(),
                                hashType
                        );

            } else {

                digest =
                        LegacySignatureHash.calculate(
                                context.transaction(),
                                context.inputIndex(),
                                scriptCode,
                                hashType
                        );
            }

            EcdsaSignature signature =
                    EcdsaSignature.fromDer(
                            der
                    );

            PublicKey publicKey =
                    PublicKey.fromBytes(
                            publicKeyBytes
                    );

            valid =
                    Secp256k1.verify(
                            digest,
                            signature,
                            publicKey
                    );

        } catch (IllegalArgumentException e) {

            /*
             * До ScriptVerifyFlags malformed DER/pubkey
             * трактуем как failed CHECKSIG.
             */
            valid = false;
        }

        machine.push(
                valid
                        ? ScriptNumber.encode(1)
                        : ScriptNumber.encode(0)
        );
    }

    private static int serializedLength(
            ScriptInstruction instruction
    ) {
        int opcode =
                instruction.opcode();

        if (!instruction.isPushData()) {
            return 1;
        }

        int dataLength =
                instruction.dataLength();

        if (opcode == Opcode.OP_0) {
            return 1;
        }

        if (opcode >= Opcode.OP_DATA_MIN
                && opcode <= Opcode.OP_DATA_MAX) {

            return Math.addExact(
                    1,
                    dataLength
            );
        }

        if (opcode == Opcode.OP_PUSHDATA1) {

            return Math.addExact(
                    2,
                    dataLength
            );
        }

        if (opcode == Opcode.OP_PUSHDATA2) {

            return Math.addExact(
                    3,
                    dataLength
            );
        }

        if (opcode == Opcode.OP_PUSHDATA4) {

            return Math.addExact(
                    5,
                    dataLength
            );
        }

        throw new ScriptExecutionException(
                "Unsupported push opcode: 0x"
                        + String.format(
                        "%02x",
                        opcode
                )
        );
    }
}