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
             * Обычная legacy проверка.
             */
            if (!isTrueTop(machine)) {
                return false;
            }

            /*
             * Если это не P2SH — на этом всё.
             */
            if (!verifyP2sh) {
                return true;
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
             * 5. redeemScript также обязан
             * завершиться true.
             *
             * CLEANSTACK здесь НЕ требуем.
             */
            return isTrueTop(
                    stackAfterScriptSig
            );

        } catch (ScriptExecutionException
                 | ScriptParseException e) {

            return false;
        }
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