package ru.bitcoin.node.script;

import ru.bitcoin.node.protocol.transaction.Transaction;

public final class LegacyScriptVerifier {

    private LegacyScriptVerifier() {
    }

    public static boolean verify(
            Transaction transaction,
            int inputIndex,
            byte[] scriptSig,
            byte[] scriptPubKey
    ) {
        return verify(
                transaction,
                inputIndex,
                scriptSig,
                scriptPubKey,
                ScriptVerifyFlags.NONE
        );
    }

    public static boolean verify(
            Transaction transaction,
            int inputIndex,
            byte[] scriptSig,
            byte[] scriptPubKey,
            int flags
    ) {
        if (transaction == null) {
            throw new IllegalArgumentException(
                    "transaction must not be null"
            );
        }

        if (scriptSig == null) {
            throw new IllegalArgumentException(
                    "scriptSig must not be null"
            );
        }

        if (scriptPubKey == null) {
            throw new IllegalArgumentException(
                    "scriptPubKey must not be null"
            );
        }

        if (inputIndex < 0
                || inputIndex >= transaction.inputs().size()) {

            throw new IllegalArgumentException(
                    "inputIndex is out of range: "
                            + inputIndex
            );
        }

        try {
            if (ScriptVerifyFlags.has(flags, ScriptVerifyFlags.SIGPUSHONLY)
                    && !P2shScript.isPushOnly(scriptSig)) {
                return false;
            }

            boolean verifyP2sh =
                    ScriptVerifyFlags.has(
                            flags,
                            ScriptVerifyFlags.P2SH
                    )
                            &&
                            P2shScript.isPayToScriptHash(
                                    scriptPubKey
                            );

            /*
             * BIP16 consensus rule:
             *
             * Для P2SH scriptSig обязан состоять
             * только из push operations.
             */
            if (verifyP2sh
                    && !P2shScript.isPushOnly(
                    scriptSig
            )) {

                return false;
            }

            /*
             * 1. Execute scriptSig.
             */
            ScriptMachine machine =
                    new ScriptMachine();

            ScriptExecutionContext scriptSigContext =
                    new ScriptExecutionContext(
                            transaction,
                            inputIndex,
                            scriptSig,
                            flags
                    );

            ScriptInterpreter.execute(
                    scriptSig,
                    machine,
                    scriptSigContext
            );

            /*
             * BIP16 требует сохранить stack
             * именно ПОСЛЕ scriptSig и ДО
             * выполнения scriptPubKey.
             */
            ScriptMachine stackAfterScriptSig =
                    verifyP2sh
                            ? machine.copy()
                            : null;

            /*
             * 2. Execute scriptPubKey using
             * the SAME stack.
             */
            ScriptExecutionContext scriptPubKeyContext =
                    new ScriptExecutionContext(
                            transaction,
                            inputIndex,
                            scriptPubKey,
                            flags
                    );

            ScriptInterpreter.execute(
                    scriptPubKey,
                    machine,
                    scriptPubKeyContext
            );

            /*
             * scriptPubKey обязан завершиться true.
             */
            if (!isTrueTop(machine)) {
                return false;
            }

            /*
             * Для обычного legacy spend это уже
             * окончательный stack.
             *
             * CLEANSTACK требует:
             *
             * stack.size() == 1
             * &&
             * единственный элемент == true
             */
            if (!verifyP2sh) {

                return isValidFinalStack(
                        machine,
                        flags
                );
            }

            /*
             * 3. Восстанавливаем stack,
             * который существовал сразу после
             * выполнения scriptSig.
             *
             * Последний элемент этого stack —
             * serialized redeemScript.
             */
            if (stackAfterScriptSig == null
                    || stackAfterScriptSig.isEmpty()) {

                return false;
            }

            byte[] redeemScript =
                    stackAfterScriptSig.pop();

            /*
             * 4. Execute redeemScript using
             * оставшийся restored stack.
             */
            ScriptExecutionContext redeemScriptContext =
                    new ScriptExecutionContext(
                            transaction,
                            inputIndex,
                            redeemScript,
                            flags
                    );

            ScriptInterpreter.execute(
                    redeemScript,
                    stackAfterScriptSig,
                    redeemScriptContext
            );

            /*
             * 5. redeemScript обязан завершиться true.
             *
             * Это окончательный stack P2SH execution,
             * поэтому именно здесь применяется CLEANSTACK.
             */
            return isValidFinalStack(
                    stackAfterScriptSig,
                    flags
            );

        } catch (ScriptExecutionException
                 | ScriptParseException e) {

            return false;
        }
    }

    private static boolean isValidFinalStack(
            ScriptMachine machine,
            int flags
    ) {

        /*
         * Сначала обычное Bitcoin Script правило:
         *
         * stack не пустой
         * и верхний элемент == true.
         */
        if (!isTrueTop(machine)) {
            return false;
        }

        /*
         * Без CLEANSTACK дополнительные
         * элементы ниже top разрешены.
         */
        if (!ScriptVerifyFlags.has(
                flags,
                ScriptVerifyFlags.CLEANSTACK
        )) {

            return true;
        }

        /*
         * CLEANSTACK:
         *
         * после полного выполнения script path
         * должен остаться ровно один элемент.
         *
         * Его true уже проверен выше.
         */
        return machine.size() == 1;
    }

    public static boolean verifyP2shOuter(
            Transaction transaction,
            int inputIndex,
            byte[] scriptSig,
            byte[] scriptPubKey,
            int flags
    ) {
        if (transaction == null) {
            throw new IllegalArgumentException(
                    "transaction must not be null"
            );
        }

        if (scriptSig == null) {
            throw new IllegalArgumentException(
                    "scriptSig must not be null"
            );
        }

        if (scriptPubKey == null) {
            throw new IllegalArgumentException(
                    "scriptPubKey must not be null"
            );
        }

        if (inputIndex < 0
                || inputIndex >= transaction.inputs().size()) {

            throw new IllegalArgumentException(
                    "inputIndex is out of range: "
                            + inputIndex
            );
        }

        /*
         * Этот метод используется только
         * для активного P2SH consensus path.
         */
        if (!ScriptVerifyFlags.has(
                flags,
                ScriptVerifyFlags.P2SH
        )) {
            return false;
        }

        if (!P2shScript.isPayToScriptHash(
                scriptPubKey
        )) {
            return false;
        }

        try {

            /*
             * BIP16:
             * scriptSig должен быть push-only.
             */
            if (!P2shScript.isPushOnly(
                    scriptSig
            )) {
                return false;
            }

            /*
             * 1. Выполняем scriptSig.
             */
            ScriptMachine machine =
                    new ScriptMachine();

            ScriptExecutionContext scriptSigContext =
                    new ScriptExecutionContext(
                            transaction,
                            inputIndex,
                            scriptSig,
                            flags
                    );

            ScriptInterpreter.execute(
                    scriptSig,
                    machine,
                    scriptSigContext
            );

            /*
             * После scriptSig должен существовать
             * хотя бы redeemScript.
             */
            if (machine.isEmpty()) {
                return false;
            }

            /*
             * 2. Выполняем только внешний
             * P2SH scriptPubKey.
             *
             * RedeemScript здесь намеренно
             * НЕ исполняем.
             */
            ScriptExecutionContext scriptPubKeyContext =
                    new ScriptExecutionContext(
                            transaction,
                            inputIndex,
                            scriptPubKey,
                            flags
                    );

            ScriptInterpreter.execute(
                    scriptPubKey,
                    machine,
                    scriptPubKeyContext
            );

            /*
             * Проверяем результат внешнего:
             *
             * HASH160(redeemScript)
             * ==
             * hash из scriptPubKey.
             */
            return isTrueTop(
                    machine
            );

        } catch (ScriptExecutionException
                 | ScriptParseException e) {

            return false;
        }
    }
    private static boolean isTrueTop(
            ScriptMachine machine
    ) {
        if (machine.isEmpty()) {
            return false;
        }

        return ScriptNumber.castToBool(
                machine.peek()
        );
    }
}
