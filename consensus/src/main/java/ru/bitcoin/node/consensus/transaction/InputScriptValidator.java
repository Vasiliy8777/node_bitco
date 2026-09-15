package ru.bitcoin.node.consensus.transaction;

import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.script.*;

import java.util.Optional;

public final class InputScriptValidator {

    private InputScriptValidator() {
    }

    public static void validate(
            Transaction transaction,
            int inputIndex,
            UtxoView utxoView,
            int scriptVerifyFlags
    ) {
        if (transaction == null) {
            throw new IllegalArgumentException(
                    "transaction must not be null"
            );
        }

        if (utxoView == null) {
            throw new IllegalArgumentException(
                    "utxoView must not be null"
            );
        }

        if (inputIndex < 0
                || inputIndex >= transaction.inputs().size()) {

            throw new IllegalArgumentException(
                    "inputIndex is out of range: "
                            + inputIndex
            );
        }

        if (transaction.isCoinbase()) {
            throw new IllegalArgumentException(
                    "Coinbase transaction has no spendable inputs"
            );
        }

        TxIn input =
                transaction.inputs().get(
                        inputIndex
                );

        if (ScriptVerifyFlags.has(scriptVerifyFlags, ScriptVerifyFlags.SIGPUSHONLY)
                && !P2shScript.isPushOnly(input.scriptSig())) {
            throw new TransactionValidationException("scriptSig is not push-only");
        }

        UtxoEntry utxo =
                utxoView.find(
                                input.previousOutput()
                        )
                        .orElseThrow(
                                () ->
                                        new TransactionValidationException(
                                                "Missing or already spent UTXO for script validation: "
                                                        + input.previousOutput()
                                        )
                        );

        byte[] scriptPubKey =
                utxo.scriptPubKey();

        /*
         * SegWit validation применяется только
         * при активном consensus WITNESS flag.
         *
         * До активации witness program является
         * обычным legacy scriptPubKey.
         */
        if (ScriptVerifyFlags.has(
                scriptVerifyFlags,
                ScriptVerifyFlags.WITNESS
        )) {

            Optional<WitnessProgram> witnessProgram =
                    WitnessProgram.parse(
                            scriptPubKey
                    );

            if (witnessProgram.isPresent()) {

                var program = witnessProgram.get();
                if (!ScriptNumber.castToBool(program.program())) {
                    throw new TransactionValidationException("Witness program evaluates to false before witness execution");
                }
                if (ScriptPubKeyClassifier.classify(scriptPubKey) == ScriptPubKeyType.ANCHOR) {
                    if (input.scriptSig().length != 0) throw new TransactionValidationException("Native anchor requires empty scriptSig");
                    return;
                }
                if (program.version() == 1 && program.programLength() == 32
                        && ScriptVerifyFlags.has(scriptVerifyFlags, ScriptVerifyFlags.TAPROOT)) {
                    if (input.scriptSig().length != 0) {
                        throw new TransactionValidationException("Native Taproot requires empty scriptSig");
                    }
                    var coins = transaction.inputs().stream().map(txIn -> {
                        var coin = utxoView.find(txIn.previousOutput()).orElseThrow(
                                () -> new TransactionValidationException("Missing Taproot input UTXO"));
                        return new ru.bitcoin.node.protocol.transaction.TxOut(coin.amount(), coin.scriptPubKey());
                    }).toList();
                    ru.bitcoin.node.script.validation.TaprootValidator.validate(
                            transaction, inputIndex, coins, program.program(), scriptVerifyFlags);
                    return;
                }

                validateNativeWitnessProgram(
                        transaction,
                        inputIndex,
                        input,
                        utxo,
                        witnessProgram.get(),
                        scriptVerifyFlags
                );

                return;
            }
        }
        /*
         * P2SH-wrapped SegWit.
         *
         * Внешний scriptPubKey является P2SH:
         *
         * OP_HASH160 <20-byte hash> OP_EQUAL
         *
         * А настоящий witness program находится
         * внутри scriptSig как redeemScript.
         */
        if (ScriptVerifyFlags.has(
                scriptVerifyFlags,
                ScriptVerifyFlags.WITNESS
        )
                && ScriptVerifyFlags.has(
                scriptVerifyFlags,
                ScriptVerifyFlags.P2SH
        )
                && P2shScript.isPayToScriptHash(
                scriptPubKey
        )) {

            Optional<WitnessProgram> wrappedWitnessProgram =
                    P2shWitnessProgram.extract(
                            input.scriptSig()
                    );

            // Detect the redeem program independently of the canonical wrapper.
            // A malformed witness wrapper must never fall back to legacy execution.
            byte[] lastPush = SigOpCounter.lastPush(input.scriptSig());
            if (lastPush != null && WitnessProgram.parse(lastPush).isPresent()
                    && wrappedWitnessProgram.isEmpty()) {
                throw new TransactionValidationException("Non-canonical P2SH witness scriptSig");
            }

            if (wrappedWitnessProgram.isPresent()) {
                if (!ScriptNumber.castToBool(wrappedWitnessProgram.get().program())) {
                    throw new TransactionValidationException("Witness redeemScript evaluates to false");
                }

                /*
                 * Проверяем только внешний P2SH:
                 *
                 * HASH160(redeemScript)
                 * должен совпасть с hash внутри
                 * scriptPubKey.
                 *
                 * redeemScript здесь НЕ исполняем
                 * как legacy script.
                 */
                boolean outerValid =
                        LegacyScriptVerifier
                                .verifyP2shOuter(
                                        transaction,
                                        inputIndex,
                                        input.scriptSig(),
                                        scriptPubKey,
                                        scriptVerifyFlags
                                );

                if (!outerValid) {
                    throw scriptValidationFailed(
                            inputIndex,
                            input
                    );
                }

                validateWrappedWitnessProgram(
                        transaction,
                        inputIndex,
                        input,
                        utxo,
                        wrappedWitnessProgram.get(),
                        scriptVerifyFlags
                );

                return;
            }
        }

        /*
         * Legacy / P2SH path.
         */
        boolean valid =
                LegacyScriptVerifier.verify(
                        transaction,
                        inputIndex,
                        input.scriptSig(),
                        scriptPubKey,
                        scriptVerifyFlags
                );

        if (!valid) {
            throw scriptValidationFailed(
                    inputIndex,
                    input
            );
        }

        /*
         * Если WITNESS rules активны, обычный
         * non-witness input не имеет права
         * содержать неожиданный witness stack.
         *
         * Иначе witness data можно было бы
         * прикреплять к legacy spend без проверки.
         */
        if (ScriptVerifyFlags.has(
                scriptVerifyFlags,
                ScriptVerifyFlags.WITNESS
        )
                && !input.witness().isEmpty()) {

            throw new TransactionValidationException(
                    "Unexpected witness data for non-witness input "
                            + inputIndex
                            + ": "
                            + input.previousOutput()
            );
        }
    }
    private static void validateWrappedWitnessProgram(
            Transaction transaction,
            int inputIndex,
            TxIn input,
            UtxoEntry utxo,
            WitnessProgram witnessProgram,
            int scriptVerifyFlags
    ) {

        /*
         * Для P2SH-wrapped witness сам scriptSig
         * НЕ передаётся в native verifier.
         *
         * Там scriptSig обязан быть empty.
         *
         * Его точное nested-форматирование уже
         * проверено P2shWitnessProgram.extract().
         */
        byte[] emptyScriptSig =
                new byte[0];

        /*
         * Witness v0:
         *
         * 20 bytes -> P2WPKH
         * 32 bytes -> P2WSH
         */
        if (witnessProgram.version() == 0) {

            if (witnessProgram.isP2wpkh()) {

                boolean valid =
                        WitnessV0ScriptVerifier
                                .verifyP2wpkh(
                                        transaction,
                                        inputIndex,
                                        emptyScriptSig,
                                        input.witness(),
                                        witnessProgram,
                                        utxo.amount(),
                                        scriptVerifyFlags
                                );

                if (!valid) {
                    throw scriptValidationFailed(
                            inputIndex,
                            input
                    );
                }

                return;
            }

            if (witnessProgram.isP2wsh()) {

                boolean valid =
                        WitnessV0ScriptVerifier
                                .verifyP2wsh(
                                        transaction,
                                        inputIndex,
                                        emptyScriptSig,
                                        input.witness(),
                                        witnessProgram,
                                        utxo.amount(),
                                        scriptVerifyFlags
                                );

                if (!valid) {
                    throw scriptValidationFailed(
                            inputIndex,
                            input
                    );
                }

                return;
            }

            /*
             * Witness v0 допускает только
             * program length 20 или 32.
             */
            throw new TransactionValidationException(
                    "Invalid wrapped witness v0 program length for input "
                            + inputIndex
                            + ": "
                            + witnessProgram.programLength()
            );
        }

        /*
         * Witness versions 1..16 пока являются
         * upgradable witness versions.
         *
         * На consensus-уровне они принимаются,
         * если для конкретной версии ещё не
         * активировано отдельное правило.
         *
         * Standard policy может их запрещать.
         */
        if (ScriptVerifyFlags.has(
                scriptVerifyFlags,
                ScriptVerifyFlags.DISCOURAGE_UPGRADABLE_WITNESS_PROGRAM
        )) {

            throw new ScriptExecutionException(
                    "Discouraged upgradable witness program"
            );
        }

        /*
         * Unknown witness version:
         * consensus-valid.
         */
    }

    private static void validateNativeWitnessProgram(
            Transaction transaction,
            int inputIndex,
            TxIn input,
            UtxoEntry utxo,
            WitnessProgram witnessProgram,
            int scriptVerifyFlags
    ) {

        /*
         * Любой native witness program
         * требует пустой scriptSig.
         *
         * Это относится не только к v0,
         * но и к unknown witness versions.
         */
        if (input.scriptSig().length != 0) {

            throw new TransactionValidationException(
                    "Native witness program requires empty scriptSig for input "
                            + inputIndex
            );
        }

        /*
         * Witness version 0 currently defines:
         *
         * 20 bytes -> P2WPKH
         * 32 bytes -> P2WSH
         *
         * Любая другая длина для witness v0
         * является consensus failure.
         */
        if (witnessProgram.version() == 0) {

            if (witnessProgram.isP2wpkh()) {

                boolean valid =
                        WitnessV0ScriptVerifier
                                .verifyP2wpkh(
                                        transaction,
                                        inputIndex,
                                        input.scriptSig(),
                                        input.witness(),
                                        witnessProgram,
                                        utxo.amount(),
                                        scriptVerifyFlags
                                );

                if (!valid) {
                    throw scriptValidationFailed(
                            inputIndex,
                            input
                    );
                }

                return;
            }

            if (witnessProgram.isP2wsh()) {

                boolean valid =
                        WitnessV0ScriptVerifier
                                .verifyP2wsh(
                                        transaction,
                                        inputIndex,
                                        input.scriptSig(),
                                        input.witness(),
                                        witnessProgram,
                                        utxo.amount(),
                                        scriptVerifyFlags
                                );

                if (!valid) {
                    throw scriptValidationFailed(
                            inputIndex,
                            input
                    );
                }

                return;
            }

            throw new TransactionValidationException(
                    "Invalid witness v0 program length for input "
                            + inputIndex
                            + ": "
                            + witnessProgram.programLength()
            );
        }

        /*
         * Witness versions 1..16:
         *
         * Пока отдельные semantics для версии
         * не активированы, consensus разрешает
         * такие witness programs для forward
         * compatibility.
         *
         * Standard policy может их запрещать.
         */
        if (ScriptVerifyFlags.has(
                scriptVerifyFlags,
                ScriptVerifyFlags.DISCOURAGE_UPGRADABLE_WITNESS_PROGRAM
        )) {

            throw new ScriptExecutionException(
                    "Discouraged upgradable witness program"
            );
        }

        /*
         * Unknown/upgradable witness version:
         * consensus-valid.
         *
         * Позже v1 будет перенаправлен в
         * BIP341/BIP342 Taproot validation.
         */
    }

    private static TransactionValidationException
    scriptValidationFailed(
            int inputIndex,
            TxIn input
    ) {
        return new TransactionValidationException(
                "Script validation failed for input "
                        + inputIndex
                        + ": "
                        + input.previousOutput()
        );
    }

    public static void validateAll(
            Transaction transaction,
            UtxoView utxoView,
            int scriptVerifyFlags
    ) {
        if (transaction == null) {
            throw new IllegalArgumentException(
                    "transaction must not be null"
            );
        }

        if (utxoView == null) {
            throw new IllegalArgumentException(
                    "utxoView must not be null"
            );
        }

        if (transaction.isCoinbase()) {
            throw new IllegalArgumentException(
                    "Coinbase transaction has no spendable inputs"
            );
        }

        for (int inputIndex = 0;
             inputIndex < transaction.inputs().size();
             inputIndex++) {

            validate(
                    transaction,
                    inputIndex,
                    utxoView,
                    scriptVerifyFlags
            );
        }
    }

}
