package ru.bitcoin.node.script;

import ru.bitcoin.node.crypto.hash.*;
import ru.bitcoin.node.crypto.secp256k1.EcdsaSignature;
import ru.bitcoin.node.crypto.secp256k1.PublicKey;
import ru.bitcoin.node.crypto.secp256k1.Secp256k1;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
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
                decodeNumber(
                        machine.peek(),
                        5,
                        context
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
                decodeNumber(
                        machine.peek(),
                        5,
                        context
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
        /*
         * Каждый вызов execute() соответствует
         * отдельному Bitcoin EvalScript.
         *
         * altstack локален одному EvalScript и не должен
         * переноситься между scriptSig, scriptPubKey,
         * redeemScript или witnessScript.
         *
         * Основной stack при этом сохраняется.
         */
        machine.clearAltStack();

        if (script.length
                > ScriptLimits.MAX_SCRIPT_SIZE) {

            throw new ScriptExecutionException(
                    "Script size exceeds consensus limit: "
                            + script.length
                            + " > "
                            + ScriptLimits.MAX_SCRIPT_SIZE
            );
        }

        List<ScriptInstruction> instructions =
                ScriptParser.parse(script);

        int opCount = 0;

        /*
         * Состояние вложенных OP_IF / OP_NOTIF.
         *
         * Последний элемент соответствует
         * самому внутреннему conditional block.
         */
        Deque<Boolean> executionConditions =
                new ArrayDeque<>();
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

            if (instruction.isPushData()
                    && instruction.dataLength()
                    > ScriptLimits.MAX_SCRIPT_ELEMENT_SIZE) {

                throw new ScriptExecutionException(
                        "Pushed element exceeds consensus limit: "
                                + instruction.dataLength()
                                + " > "
                                + ScriptLimits.MAX_SCRIPT_ELEMENT_SIZE
                );
            }

            int opcode =
                    instruction.opcode();

            boolean executing =
                    isExecuting(
                            executionConditions
                    );

            /*
             * Bitcoin Script считает каждый opcode > OP_16.
             *
             * Push-операции и OP_0..OP_16 сюда не входят.
             */
            if (instruction.opcode()
                    > Opcode.OP_16) {

                opCount++;

                if (opCount
                        > ScriptLimits.MAX_OPS_PER_SCRIPT) {

                    throw new ScriptExecutionException(
                            "Script operation count exceeds consensus limit of "
                                    + ScriptLimits.MAX_OPS_PER_SCRIPT
                    );
                }
            }

            /*
             * Historical disabled opcodes.
             *
             * Bitcoin consensus запрещает их безусловно:
             * даже если opcode находится внутри false branch.
             */
            if (!instruction.isPushData()
                    && isDisabledOpcode(opcode)) {

                throw new ScriptExecutionException(
                        "Disabled opcode: 0x"
                                + String.format(
                                "%02x",
                                opcode
                        )
                );
            }

            /*
             * OP_VERIF / OP_VERNOTIF также всегда являются ошибкой.
             *
             * Они должны fail даже внутри неисполняемой conditional branch.
             */
            if (!instruction.isPushData()
                    && (opcode == Opcode.OP_VERIF
                    || opcode == Opcode.OP_VERNOTIF)) {

                throw new ScriptExecutionException(
                        "Reserved conditional opcode: 0x"
                                + String.format(
                                "%02x",
                                opcode
                        )
                );
            }

            /*
             * Conditional opcodes обрабатываются даже внутри
             * неисполняемой ветки, потому что они задают
             * структуру самого Script.
             */
            if (!instruction.isPushData()) {

                if (opcode == Opcode.OP_IF
                        || opcode == Opcode.OP_NOTIF) {

                    boolean condition = false;

                    /*
                     * Если родительская ветка активна,
                     * IF/NOTIF действительно читает stack.
                     *
                     * Если родительская ветка неактивна,
                     * stack не трогаем и просто добавляем false.
                     */
                    if (executing) {

                        machine.requireStackSize(1);

                        byte[] conditionValue =
                                machine.pop();

                        if (requiresMinimalIf(context)
                                && !isMinimalIfValue(conditionValue)) {

                            throw new ScriptExecutionException(
                                    "OP_IF/OP_NOTIF requires minimal boolean"
                            );
                        }

                        condition =
                                ScriptNumber.castToBool(
                                        conditionValue
                                );

                        if (opcode
                                == Opcode.OP_NOTIF) {

                            condition =
                                    !condition;
                        }
                    }

                    executionConditions.addLast(
                            executing && condition
                    );

                    machine.validateStackSize();

                    continue;
                }

                if (opcode == Opcode.OP_ELSE) {

                    if (executionConditions.isEmpty()) {

                        throw new ScriptExecutionException(
                                "OP_ELSE without matching OP_IF/OP_NOTIF"
                        );
                    }

                    boolean current =
                            executionConditions.removeLast();

                    executionConditions.addLast(
                            !current
                    );

                    machine.validateStackSize();

                    continue;
                }

                if (opcode == Opcode.OP_ENDIF) {

                    if (executionConditions.isEmpty()) {

                        throw new ScriptExecutionException(
                                "OP_ENDIF without matching OP_IF/OP_NOTIF"
                        );
                    }

                    executionConditions.removeLast();

                    machine.validateStackSize();

                    continue;
                }
            }

            executing =
                    isExecuting(
                            executionConditions
                    );

            /*
             * Обычные opcodes и pushes внутри false-ветки
             * не выполняются.
             *
             * Но parsing, push-size и op-count уже произошли выше.
             */
            if (!executing) {

                machine.validateStackSize();

                continue;
            }

            if (!instruction.isPushData()
                    && opcode == Opcode.OP_CODESEPARATOR) {

                lastCodeSeparatorOffset =
                        nextInstructionOffset;

                machine.validateStackSize();

                continue;
            }

            opCount =
                    executeInstruction(
                            instruction,
                            machine,
                            context,
                            lastCodeSeparatorOffset,
                            opCount
                    );

            /*
             * Bitcoin consensus:
             *
             * после выполнения opcode суммарное количество
             * элементов main stack + altstack
             * не должно превышать 1000.
             */
            machine.validateStackSize();

            if (opCount
                    > ScriptLimits.MAX_OPS_PER_SCRIPT) {

                throw new ScriptExecutionException(
                        "Script operation count exceeds consensus limit of "
                                + ScriptLimits.MAX_OPS_PER_SCRIPT
                );
            }
        }

        if (!executionConditions.isEmpty()) {

            throw new ScriptExecutionException(
                    "Unbalanced conditional: missing OP_ENDIF"
            );
        }

        if (nextInstructionOffset
                != script.length) {

            throw new ScriptExecutionException(
                    "Script instruction offsets do not match script length"
            );
        }
    }

    private static int executeInstruction(
            ScriptInstruction instruction,
            ScriptMachine machine,
            ScriptExecutionContext context,
            int lastCodeSeparatorOffset,
            int opCount
    ) {

        if (instruction.isPushData()) {

            byte[] data =
                    instruction.data();

            if (data == null) {
                data =
                        new byte[0];
            }

            boolean requireMinimal =
                    context != null
                            && ScriptVerifyFlags.has(
                            context.flags(),
                            ScriptVerifyFlags.MINIMALDATA
                    );

            if (requireMinimal
                    && !MinimalPush.isMinimal(
                    instruction.opcode(),
                    data
            )) {

                throw new ScriptExecutionException(
                        "Non-minimal data push"
                );
            }

            machine.push(
                    data
            );

            return opCount;
        }

        int opcode =
                instruction.opcode();

        if (opcode == Opcode.OP_1NEGATE) {

            machine.push(
                    ScriptNumber.encode(-1)
            );

            return opCount;
        }

        if (Opcode.isSmallInteger(opcode)) {

            long value =
                    opcode
                            - Opcode.OP_1
                            + 1L;

            machine.push(
                    ScriptNumber.encode(value)
            );

            return opCount;
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

            case Opcode.OP_NOP -> {
                /*
                 * Consensus NOP.
                 */
            }

            case Opcode.OP_NOP1,
                 Opcode.OP_NOP4,
                 Opcode.OP_NOP5,
                 Opcode.OP_NOP6,
                 Opcode.OP_NOP7,
                 Opcode.OP_NOP8,
                 Opcode.OP_NOP9,
                 Opcode.OP_NOP10 -> {

                /*
                 * Эти opcodes являются consensus-valid NOP,
                 * но standard policy может запрещать их,
                 * чтобы будущие soft fork upgrades могли
                 * безопасно изменить их semantics.
                 */
                if (context != null
                        && ScriptVerifyFlags.has(
                        context.flags(),
                        ScriptVerifyFlags.DISCOURAGE_UPGRADABLE_NOPS
                )) {

                    throw new ScriptExecutionException(
                            "Discouraged upgradable NOP: 0x"
                                    + String.format(
                                    "%02x",
                                    opcode
                            )
                    );
                }
            }

            case Opcode.OP_RETURN ->
                    throw new ScriptExecutionException(
                            "OP_RETURN encountered"
                    );

            case Opcode.OP_RESERVED,
                 Opcode.OP_VER,
                 Opcode.OP_RESERVED1,
                 Opcode.OP_RESERVED2 ->
                    throw new ScriptExecutionException(
                            "Reserved opcode: 0x"
                                    + String.format(
                                    "%02x",
                                    opcode
                            )
                    );

            case Opcode.OP_DUP ->
                    machine.duplicateTop();

            case Opcode.OP_DROP ->
                    machine.pop();

            case Opcode.OP_2DROP -> {

                machine.requireStackSize(2);

                machine.pop();
                machine.pop();
            }

            case Opcode.OP_2DUP -> {

                machine.requireStackSize(2);

                byte[] second =
                        machine.peekFromTop(1);

                byte[] first =
                        machine.peekFromTop(0);

                machine.push(second);
                machine.push(first);
            }

            case Opcode.OP_3DUP -> {

                machine.requireStackSize(3);

                byte[] third =
                        machine.peekFromTop(2);

                byte[] second =
                        machine.peekFromTop(1);

                byte[] first =
                        machine.peekFromTop(0);

                machine.push(third);
                machine.push(second);
                machine.push(first);
            }

            case Opcode.OP_2OVER -> {

                machine.requireStackSize(4);

                byte[] fourth =
                        machine.peekFromTop(3);

                byte[] third =
                        machine.peekFromTop(2);

                machine.push(fourth);
                machine.push(third);
            }

            case Opcode.OP_2ROT -> {

                machine.requireStackSize(6);

                byte[] sixth =
                        machine.removeFromTop(5);

                byte[] fifth =
                        machine.removeFromTop(4);

                machine.push(sixth);
                machine.push(fifth);
            }

            case Opcode.OP_2SWAP -> {

                machine.requireStackSize(4);

                byte[] fourth =
                        machine.removeFromTop(3);

                byte[] third =
                        machine.removeFromTop(2);

                machine.push(fourth);
                machine.push(third);
            }

            case Opcode.OP_IFDUP -> {

                machine.requireStackSize(1);

                if (ScriptNumber.castToBool(
                        machine.peek()
                )) {

                    machine.duplicateTop();
                }
            }

            case Opcode.OP_DEPTH ->

                    machine.push(
                            ScriptNumber.encode(
                                    machine.size()
                            )
                    );

            case Opcode.OP_NIP -> {

                machine.requireStackSize(2);

                machine.removeFromTop(1);
            }

            case Opcode.OP_OVER -> {

                machine.requireStackSize(2);

                machine.push(
                        machine.peekFromTop(1)
                );
            }

            case Opcode.OP_PICK ->
                    executePickOrRoll(
                            machine,
                            false,
                            context
                    );

            case Opcode.OP_ROLL ->
                    executePickOrRoll(
                            machine,
                            true,
                            context
                    );

            case Opcode.OP_ROT -> {

                machine.requireStackSize(3);

                byte[] value =
                        machine.removeFromTop(2);

                machine.push(value);
            }

            case Opcode.OP_SWAP -> {

                machine.requireStackSize(2);

                byte[] value =
                        machine.removeFromTop(1);

                machine.push(value);
            }

            case Opcode.OP_TUCK -> {

                machine.requireStackSize(2);

                byte[] top =
                        machine.pop();

                byte[] second =
                        machine.pop();

                machine.push(top);
                machine.push(second);
                machine.push(top);
            }

            case Opcode.OP_1ADD,
                 Opcode.OP_1SUB,
                 Opcode.OP_NEGATE,
                 Opcode.OP_ABS,
                 Opcode.OP_NOT,
                 Opcode.OP_0NOTEQUAL ->
                    executeUnaryNumeric(
                            machine,
                            opcode,
                            context
                    );

            case Opcode.OP_SIZE -> {

                machine.requireStackSize(1);

                machine.push(
                        ScriptNumber.encode(
                                machine.peek().length
                        )
                );
            }

            case Opcode.OP_TOALTSTACK -> {

                machine.requireStackSize(1);

                machine.pushAlt(
                        machine.pop()
                );
            }

            case Opcode.OP_FROMALTSTACK -> {

                machine.requireAltStackSize(1);

                machine.push(
                        machine.popAlt()
                );
            }

            case Opcode.OP_EQUAL ->
                    executeEqual(machine);

            case Opcode.OP_EQUALVERIFY -> {
                executeEqual(machine);
                executeVerify(machine);
            }

            case Opcode.OP_VERIFY ->
                    executeVerify(machine);

            case Opcode.OP_RIPEMD160,
                 Opcode.OP_SHA1,
                 Opcode.OP_SHA256,
                 Opcode.OP_HASH160,
                 Opcode.OP_HASH256 ->
                    executeHash(
                            machine,
                            opcode
                    );

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

            case Opcode.OP_ADD,
                 Opcode.OP_SUB,
                 Opcode.OP_BOOLAND,
                 Opcode.OP_BOOLOR,
                 Opcode.OP_NUMEQUAL,
                 Opcode.OP_NUMNOTEQUAL,
                 Opcode.OP_LESSTHAN,
                 Opcode.OP_GREATERTHAN,
                 Opcode.OP_LESSTHANOREQUAL,
                 Opcode.OP_GREATERTHANOREQUAL,
                 Opcode.OP_MIN,
                 Opcode.OP_MAX ->
                    executeBinaryNumeric(
                            machine,
                            opcode,
                            context
                    );

            case Opcode.OP_NUMEQUALVERIFY -> {

                executeBinaryNumeric(
                        machine,
                        Opcode.OP_NUMEQUAL,
                        context
                );

                executeVerify(
                        machine
                );
            }

            case Opcode.OP_WITHIN ->
                    executeWithin(
                            machine,
                            context
                    );

            case Opcode.OP_CHECKMULTISIG -> {
                return executeCheckMultiSig(
                        machine,
                        context,
                        lastCodeSeparatorOffset,
                        opCount
                );
            }

            case Opcode.OP_CHECKMULTISIGVERIFY -> {

                int updatedOpCount =
                        executeCheckMultiSig(
                                machine,
                                context,
                                lastCodeSeparatorOffset,
                                opCount
                        );

                executeVerify(
                        machine
                );

                return updatedOpCount;
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
        return opCount;
    }

    private static void executeBinaryNumeric(
            ScriptMachine machine,
            int opcode,
            ScriptExecutionContext context
    ) {
        machine.requireStackSize(2);

        /*
         * Stack:
         *
         * ... a b
         *
         * b = top.
         *
         * Для non-commutative operations:
         *
         * OP_SUB => a - b
         * OP_LESSTHAN => a < b
         */
        long b =
                decodeNumber(
                        machine.pop(),
                        4,
                        context
                );

        long a =
                decodeNumber(
                        machine.pop(),
                        4,
                        context
                );

        long result;

        switch (opcode) {

            case Opcode.OP_ADD ->
                    result =
                            a + b;

            case Opcode.OP_SUB ->
                    result =
                            a - b;

            case Opcode.OP_BOOLAND ->
                    result =
                            a != 0
                                    && b != 0
                                    ? 1
                                    : 0;

            case Opcode.OP_BOOLOR ->
                    result =
                            a != 0
                                    || b != 0
                                    ? 1
                                    : 0;

            case Opcode.OP_NUMEQUAL ->
                    result =
                            a == b
                                    ? 1
                                    : 0;

            case Opcode.OP_NUMNOTEQUAL ->
                    result =
                            a != b
                                    ? 1
                                    : 0;

            case Opcode.OP_LESSTHAN ->
                    result =
                            a < b
                                    ? 1
                                    : 0;

            case Opcode.OP_GREATERTHAN ->
                    result =
                            a > b
                                    ? 1
                                    : 0;

            case Opcode.OP_LESSTHANOREQUAL ->
                    result =
                            a <= b
                                    ? 1
                                    : 0;

            case Opcode.OP_GREATERTHANOREQUAL ->
                    result =
                            a >= b
                                    ? 1
                                    : 0;

            case Opcode.OP_MIN ->
                    result =
                            Math.min(
                                    a,
                                    b
                            );

            case Opcode.OP_MAX ->
                    result =
                            Math.max(
                                    a,
                                    b
                            );

            default ->
                    throw new IllegalArgumentException(
                            "Unsupported binary numeric opcode: 0x"
                                    + String.format(
                                    "%02x",
                                    opcode
                            )
                    );
        }

        machine.push(
                ScriptNumber.encode(
                        result
                )
        );
    }

    private static void executeWithin(
            ScriptMachine machine,
            ScriptExecutionContext context
    ) {
        machine.requireStackSize(3);

        /*
         * Stack:
         *
         * ... x min max
         *
         * Result:
         *
         * min <= x && x < max
         *
         * Верхняя граница НЕ включается.
         */
        long max =
                decodeNumber(
                        machine.pop(),
                        4,
                        context
                );

        long min =
                decodeNumber(
                        machine.pop(),
                        4,
                        context
                );

        long x =
                decodeNumber(
                        machine.pop(),
                        4,
                        context
                );

        machine.push(
                ScriptNumber.encode(
                        min <= x
                                && x < max
                                ? 1
                                : 0
                )
        );
    }

    private static void executeUnaryNumeric(
            ScriptMachine machine,
            int opcode,
            ScriptExecutionContext context
    ) {
        machine.requireStackSize(1);

        /*
         * Bitcoin numeric opcodes используют
         * CScriptNum с максимальным размером operand = 4 bytes.
         *
         * Сам результат может получиться 5-byte.
         */
        long value =
                decodeNumber(
                        machine.pop(),
                        4,
                        context
                );

        long result;

        switch (opcode) {

            case Opcode.OP_1ADD ->
                    result =
                            value + 1;

            case Opcode.OP_1SUB ->
                    result =
                            value - 1;

            case Opcode.OP_NEGATE ->
                    result =
                            -value;

            case Opcode.OP_ABS ->
                    result =
                            value < 0
                                    ? -value
                                    : value;

            case Opcode.OP_NOT ->
                    result =
                            value == 0
                                    ? 1
                                    : 0;

            case Opcode.OP_0NOTEQUAL ->
                    result =
                            value == 0
                                    ? 0
                                    : 1;

            default ->
                    throw new IllegalArgumentException(
                            "Unsupported unary numeric opcode: 0x"
                                    + String.format(
                                    "%02x",
                                    opcode
                            )
                    );
        }

        machine.push(
                ScriptNumber.encode(
                        result
                )
        );
    }

    private static void executePickOrRoll(
            ScriptMachine machine,
            boolean roll,
            ScriptExecutionContext context
    ) {
        machine.requireStackSize(1);

        long depthLong =
                decodeNumber(
                        machine.pop(),
                        4,
                        context
                );

        if (depthLong < 0
                || depthLong > Integer.MAX_VALUE) {

            throw new ScriptExecutionException(
                    "OP_PICK/OP_ROLL invalid stack depth: "
                            + depthLong
            );
        }

        int depth =
                (int) depthLong;

        machine.requireStackSize(
                depth + 1
        );

        byte[] value;

        if (roll) {

            value =
                    machine.removeFromTop(
                            depth
                    );

        } else {

            value =
                    machine.peekFromTop(
                            depth
                    );
        }

        machine.push(value);
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

    private static void executeHash(
            ScriptMachine machine,
            int opcode
    ) {
        machine.requireStackSize(1);

        byte[] value =
                machine.pop();

        byte[] result;

        switch (opcode) {

            case Opcode.OP_RIPEMD160 ->
                    result =
                            Ripemd160.hash(
                                    value
                            );

            case Opcode.OP_SHA1 ->
                    result =
                            Sha1.hash(
                                    value
                            );

            case Opcode.OP_SHA256 ->
                    result =
                            Sha256.hash(
                                    value
                            );

            case Opcode.OP_HASH160 ->
                    result =
                            Hash160.hash(
                                    value
                            );

            case Opcode.OP_HASH256 ->
                    result =
                            Hash256Digest.hashBytes(
                                    value
                            );

            default ->
                    throw new IllegalArgumentException(
                            "Unsupported hash opcode: 0x"
                                    + String.format(
                                    "%02x",
                                    opcode
                            )
                    );
        }

        machine.push(
                result
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
                context.flags(),
                context.signatureVersion()
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

                byte[] originalScriptCode =
                        scriptCode;

                byte[] filteredScriptCode =
                        LegacyScriptCode.findAndDeleteSignature(
                                originalScriptCode,
                                signatureWithHashType
                        );

                /*
                 * Historical legacy behavior позволяет
                 * FindAndDelete удалить serialized push
                 * текущей signature из scriptCode.
                 *
                 * CONST_SCRIPTCODE запрещает именно
                 * МОДИФИКАЦИЮ scriptCode.
                 */
                if (ScriptVerifyFlags.has(
                        context.flags(),
                        ScriptVerifyFlags.CONST_SCRIPTCODE
                )
                        && !Arrays.equals(
                        originalScriptCode,
                        filteredScriptCode
                )) {

                    throw new ScriptExecutionException(
                            "CONST_SCRIPTCODE: signature found in scriptCode"
                    );
                }

                scriptCode =
                        filteredScriptCode;
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
        /*
         * NULLFAIL:
         *
         * если CHECKSIG вернул false,
         * непустая signature запрещена.
         *
         * Пустая signature разрешена и остаётся
         * обычным false.
         */
        if (!valid
                && ScriptVerifyFlags.has(
                context.flags(),
                ScriptVerifyFlags.NULLFAIL
        )
                && signatureWithHashType.length != 0) {

            throw new ScriptExecutionException(
                    "NULLFAIL: non-empty signature failed OP_CHECKSIG"
            );
        }


        machine.push(
                valid
                        ? ScriptNumber.encode(1)
                        : ScriptNumber.encode(0)
        );
    }

    private static int executeCheckMultiSig(
            ScriptMachine machine,
            ScriptExecutionContext context,
            int lastCodeSeparatorOffset,
            int opCount
    ) {
        if (context == null) {
            throw new ScriptExecutionException(
                    "OP_CHECKMULTISIG requires transaction context"
            );
        }

        /*
         * Stack layout перед CHECKMULTISIG:
         *
         * <dummy>
         * <sig1> ... <sigM>
         * <M>
         * <pubkey1> ... <pubkeyN>
         * <N>
         *
         * Верхушка stack = N.
         */
        machine.requireStackSize(1);

        long publicKeyCountLong =
                decodeNumber(
                        machine.peek(),
                        4,
                        context
                );

        if (publicKeyCountLong < 0
                || publicKeyCountLong
                > ScriptLimits.MAX_PUBKEYS_PER_MULTISIG) {

            throw new ScriptExecutionException(
                    "OP_CHECKMULTISIG public key count out of range: "
                            + publicKeyCountLong
            );
        }

        int publicKeyCount =
                (int) publicKeyCountLong;

        int updatedOpCount =
                Math.addExact(
                        opCount,
                        publicKeyCount
                );

        if (updatedOpCount
                > ScriptLimits.MAX_OPS_PER_SCRIPT) {

            throw new ScriptExecutionException(
                    "Script operation count exceeds consensus limit of "
                            + ScriptLimits.MAX_OPS_PER_SCRIPT
            );
        }

        /*
         * Только теперь удаляем N.
         */
        machine.pop();

        machine.requireStackSize(
                publicKeyCount + 1
        );

        byte[][] publicKeys =
                new byte[publicKeyCount][];

        /*
         * Pubkeys снимаются с вершины в обратном
         * stack-порядке, поэтому сохраняем их
         * обратно в исходном порядке.
         */
        for (int i = publicKeyCount - 1;
             i >= 0;
             i--) {

            publicKeys[i] =
                    machine.pop();
        }

        long signatureCountLong =
                decodeNumber(
                        machine.pop(),
                        4,
                        context
                );

        if (signatureCountLong < 0
                || signatureCountLong > publicKeyCount) {

            throw new ScriptExecutionException(
                    "OP_CHECKMULTISIG signature count out of range: "
                            + signatureCountLong
            );
        }

        int signatureCount =
                (int) signatureCountLong;

        /*
         * Нужно:
         *
         * M signatures + historical dummy.
         */
        machine.requireStackSize(
                signatureCount + 1
        );

        byte[][] signatures =
                new byte[signatureCount][];

        for (int i = signatureCount - 1;
             i >= 0;
             i--) {

            signatures[i] =
                    machine.pop();
        }

        /*
         * Исторический CHECKMULTISIG bug:
         * opcode дополнительно потребляет один
         * элемент stack.
         */
        byte[] dummy =
                machine.pop();

        if (ScriptVerifyFlags.has(
                context.flags(),
                ScriptVerifyFlags.NULLDUMMY
        )
                && dummy.length != 0) {

            throw new ScriptExecutionException(
                    "OP_CHECKMULTISIG dummy argument must be empty"
            );
        }

        /*
         * scriptCode определяется один раз для
         * этой CHECKMULTISIG operation.
         */
        byte[] scriptCode =
                LegacyScriptCode.afterCodeSeparator(
                        context.scriptCode(),
                        lastCodeSeparatorOffset
                );

        /*
         * Legacy CHECKMULTISIG FindAndDelete удаляет
         * из scriptCode ВСЕ signatures.
         *
         * Для witness v0 FindAndDelete отсутствует.
         */
        if (context.signatureVersion()
                == SignatureVersion.LEGACY) {

            for (byte[] signature : signatures) {

                byte[] originalScriptCode =
                        scriptCode;

                byte[] filteredScriptCode =
                        LegacyScriptCode.findAndDeleteSignature(
                                originalScriptCode,
                                signature
                        );

                if (ScriptVerifyFlags.has(
                        context.flags(),
                        ScriptVerifyFlags.CONST_SCRIPTCODE
                )
                        && !Arrays.equals(
                        originalScriptCode,
                        filteredScriptCode
                )) {

                    throw new ScriptExecutionException(
                            "CONST_SCRIPTCODE: signature found in scriptCode"
                    );
                }

                scriptCode =
                        filteredScriptCode;
            }
        }

        boolean success =
                verifyMultiSignature(
                        signatures,
                        publicKeys,
                        context,
                        scriptCode
                );

        if (!success
                && ScriptVerifyFlags.has(
                context.flags(),
                ScriptVerifyFlags.NULLFAIL
        )) {

            for (byte[] signature : signatures) {

                if (signature.length != 0) {

                    throw new ScriptExecutionException(
                            "NULLFAIL: non-empty signature failed OP_CHECKMULTISIG"
                    );
                }
            }
        }

        machine.push(
                success
                        ? ScriptNumber.encode(1)
                        : ScriptNumber.encode(0)
        );
        /*
         * Bitcoin consensus rule:
         *
         * кроме самого OP_CHECKMULTISIG,
         * который уже был посчитан как один opcode,
         * op-count дополнительно увеличивается
         * на количество public keys N.
         */
        return updatedOpCount;
    }

    private static boolean verifyMultiSignature(
            byte[][] signatures,
            byte[][] publicKeys,
            ScriptExecutionContext context,
            byte[] scriptCode
    ) {
        int signatureIndex = 0;
        int publicKeyIndex = 0;

        while (signatureIndex < signatures.length
                && publicKeyIndex < publicKeys.length) {

            /*
             * Если оставшихся pubkeys уже меньше,
             * чем оставшихся signatures,
             * успех невозможен.
             */
            int signaturesRemaining =
                    signatures.length
                            - signatureIndex;

            int publicKeysRemaining =
                    publicKeys.length
                            - publicKeyIndex;

            if (signaturesRemaining
                    > publicKeysRemaining) {

                return false;
            }

            byte[] signature =
                    signatures[
                            signatureIndex
                            ];

            byte[] publicKey =
                    publicKeys[
                            publicKeyIndex
                            ];

            if (checkSignature(
                    signature,
                    publicKey,
                    context,
                    scriptCode
            )) {

                signatureIndex++;
            }

            publicKeyIndex++;
        }

        return signatureIndex
                == signatures.length;
    }

    private static boolean checkSignature(
            byte[] signatureWithHashType,
            byte[] publicKeyBytes,
            ScriptExecutionContext context,
            byte[] scriptCode
    ) {
        /*
         * Пустая подпись просто не совпадает
         * с данным public key.
         */
        if (signatureWithHashType.length == 0) {
            return false;
        }

        /*
         * Encoding failures при активных flags
         * являются Script failure и НЕ должны
         * превращаться в обычный false.
         */
        SignatureEncoding.validateSignature(
                signatureWithHashType,
                context.flags()
        );

        SignatureEncoding.validatePublicKey(
                publicKeyBytes,
                context.flags(),
                context.signatureVersion()
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

        try {

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

            return Secp256k1.verify(
                    digest,
                    signature,
                    publicKey
            );

        } catch (IllegalArgumentException e) {

            return false;
        }
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
    private static boolean isExecuting(
            Deque<Boolean> executionConditions
    ) {
        for (boolean condition
                : executionConditions) {

            if (!condition) {
                return false;
            }
        }

        return true;
    }
    private static boolean isDisabledOpcode(
            int opcode
    ) {
        return opcode == Opcode.OP_CAT
                || opcode == Opcode.OP_SUBSTR
                || opcode == Opcode.OP_LEFT
                || opcode == Opcode.OP_RIGHT
                || opcode == Opcode.OP_INVERT
                || opcode == Opcode.OP_AND
                || opcode == Opcode.OP_OR
                || opcode == Opcode.OP_XOR
                || opcode == Opcode.OP_2MUL
                || opcode == Opcode.OP_2DIV
                || opcode == Opcode.OP_MUL
                || opcode == Opcode.OP_DIV
                || opcode == Opcode.OP_MOD
                || opcode == Opcode.OP_LSHIFT
                || opcode == Opcode.OP_RSHIFT;
    }
    private static long decodeNumber(
            byte[] value,
            int maxNumSize,
            ScriptExecutionContext context
    ) {
        boolean requireMinimal =
                context != null
                        && ScriptVerifyFlags.has(
                        context.flags(),
                        ScriptVerifyFlags.MINIMALDATA
                );

        return ScriptNumber.decode(
                value,
                maxNumSize,
                requireMinimal
        );
    }
    private static boolean requiresMinimalIf(
            ScriptExecutionContext context
    ) {
        return context != null
                && context.signatureVersion()
                == SignatureVersion.WITNESS_V0
                && ScriptVerifyFlags.has(
                context.flags(),
                ScriptVerifyFlags.MINIMALIF
        );
    }

    private static boolean isMinimalIfValue(
            byte[] value
    ) {
        return value.length == 0
                || (value.length == 1
                && value[0] == 0x01);
    }
}