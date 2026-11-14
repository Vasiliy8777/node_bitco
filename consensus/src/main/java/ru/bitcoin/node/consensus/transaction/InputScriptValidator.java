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
        validate(transaction, inputIndex, utxoView, scriptVerifyFlags, false);
    }

    /** Diagnostic form for RPC probes; boolean-verifier callers keep their existing behavior. */
    public static void validate(Transaction transaction, int inputIndex, UtxoView utxoView,
                                int scriptVerifyFlags, boolean detailed) {
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
            throw scriptFailure(ScriptError.SIG_PUSHONLY, "scriptSig is not push-only", detailed);
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

        validateScripts(transaction, inputIndex, utxo.amount(), utxo.scriptPubKey(),
                scriptVerifyFlags, detailed, utxoView, null, null);
    }

    /** Stateless Script verification for offline transaction data, without transaction-level coin checks. */
    public static void validateKnownInput(Transaction transaction, int inputIndex,
                                          java.util.List<ru.bitcoin.node.protocol.transaction.TxOut> spentOutputs,
                                          int scriptVerifyFlags) {
        validateKnownInputData(transaction, inputIndex, spentOutputs, null, scriptVerifyFlags);
    }

    /** Verify supplied signature data against the immutable unsigned transaction. */
    public static void validateKnownInputData(Transaction transaction, int inputIndex,
                                              java.util.List<ru.bitcoin.node.protocol.transaction.TxOut> spentOutputs,
                                              byte[] scriptSig, ru.bitcoin.node.protocol.transaction.Witness witness,
                                              int scriptVerifyFlags) {
        verifyKnownInputData(transaction,inputIndex,spentOutputs,scriptSig,witness,scriptVerifyFlags,SignatureCheckMode.VERIFY);
    }

    /** Offline size estimation with Core's dummy signature/timelock checker; never an admission check. */
    public static void estimateKnownInputData(Transaction transaction,int inputIndex,
                                             java.util.List<ru.bitcoin.node.protocol.transaction.TxOut> spentOutputs,
                                             byte[] scriptSig,ru.bitcoin.node.protocol.transaction.Witness witness,int flags) {
        verifyKnownInputData(transaction,inputIndex,spentOutputs,scriptSig,witness,flags,SignatureCheckMode.ESTIMATE);
    }

    /** Offline VerifyScript without BIP341 precomputed transaction data, matching Core's PSBT probe. */
    public static void validateKnownInputWithoutPrecomputedData(Transaction transaction,int inputIndex,
                                             java.util.List<ru.bitcoin.node.protocol.transaction.TxOut> spentOutputs,
                                             byte[] scriptSig,ru.bitcoin.node.protocol.transaction.Witness witness,int flags) {
        verifyKnownInputData(transaction,inputIndex,spentOutputs,scriptSig,witness,flags,SignatureCheckMode.VERIFY_WITHOUT_PRECOMPUTED);
    }

    private static void verifyKnownInputData(Transaction transaction,int inputIndex,
                                             java.util.List<ru.bitcoin.node.protocol.transaction.TxOut> spentOutputs,
                                             byte[] scriptSig,ru.bitcoin.node.protocol.transaction.Witness witness,int scriptVerifyFlags,
                                             SignatureCheckMode mode) {
        java.util.Objects.requireNonNull(transaction);
        java.util.Objects.requireNonNull(scriptSig);
        java.util.Objects.requireNonNull(witness);
        if (inputIndex < 0 || inputIndex >= transaction.inputs().size())
            throw new IllegalArgumentException("Input index out of range");
        TxIn original = transaction.inputs().get(inputIndex);
        TxIn supplied = new TxIn(original.previousOutput(), scriptSig, original.sequence(), witness);
        validateKnownInputData(transaction, inputIndex, spentOutputs, supplied, scriptVerifyFlags,mode);
    }

    private static void validateKnownInputData(Transaction transaction, int inputIndex,
                                               java.util.List<ru.bitcoin.node.protocol.transaction.TxOut> spentOutputs,
                                               TxIn suppliedInput, int scriptVerifyFlags) {
        validateKnownInputData(transaction,inputIndex,spentOutputs,suppliedInput,scriptVerifyFlags,SignatureCheckMode.VERIFY);
    }
    private static void validateKnownInputData(Transaction transaction,int inputIndex,
                                               java.util.List<ru.bitcoin.node.protocol.transaction.TxOut> spentOutputs,
                                               TxIn suppliedInput,int scriptVerifyFlags,SignatureCheckMode mode) {
        java.util.Objects.requireNonNull(transaction);
        java.util.Objects.requireNonNull(spentOutputs);
        if (inputIndex < 0 || inputIndex >= transaction.inputs().size()
                || spentOutputs.size() != transaction.inputs().size())
            throw new IllegalArgumentException("Input and spent output counts do not match");
        var coins = spentOutputs;
        var coin = coins.get(inputIndex);
        if (coin == null) throw new TransactionValidationException("Missing input UTXO");
        validateScripts(transaction, inputIndex, coin.value(), coin.scriptPubKey(),
                scriptVerifyFlags, false, null, coins, suppliedInput,mode);
    }

    private static void validateScripts(Transaction transaction, int inputIndex,
                                        long amount, byte[] scriptPubKey,
                                        int scriptVerifyFlags, boolean detailed,
                                        UtxoView utxoView,
                                        java.util.List<ru.bitcoin.node.protocol.transaction.TxOut> knownSpentOutputs,
                                        TxIn suppliedInput) {
        validateScripts(transaction,inputIndex,amount,scriptPubKey,scriptVerifyFlags,detailed,utxoView,knownSpentOutputs,suppliedInput,SignatureCheckMode.VERIFY);
    }
    private static void validateScripts(Transaction transaction,int inputIndex,long amount,byte[] scriptPubKey,
                                        int scriptVerifyFlags,boolean detailed,UtxoView utxoView,
                                        java.util.List<ru.bitcoin.node.protocol.transaction.TxOut> knownSpentOutputs,
                                        TxIn suppliedInput,SignatureCheckMode mode) {
        TxIn input = suppliedInput == null ? transaction.inputs().get(inputIndex) : suppliedInput;
        if (ScriptVerifyFlags.has(scriptVerifyFlags, ScriptVerifyFlags.SIGPUSHONLY)
                && !P2shScript.isPushOnly(input.scriptSig()))
            throw scriptFailure(ScriptError.SIG_PUSHONLY, "scriptSig is not push-only", detailed);
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
                    throw scriptFailure(ScriptError.EVAL_FALSE, "Witness program evaluates to false before witness execution", detailed);
                }
                if (ScriptPubKeyClassifier.classify(scriptPubKey) == ScriptPubKeyType.ANCHOR) {
                    if (input.scriptSig().length != 0) throw scriptFailure(ScriptError.WITNESS_MALLEATED, "Native anchor requires empty scriptSig", detailed);
                    return;
                }
                if (program.version() == 1 && program.programLength() == 32
                        && ScriptVerifyFlags.has(scriptVerifyFlags, ScriptVerifyFlags.TAPROOT)) {
                    if (input.scriptSig().length != 0) {
                        throw scriptFailure(ScriptError.WITNESS_MALLEATED, "Native Taproot requires empty scriptSig", detailed);
                    }
                    java.util.List<ru.bitcoin.node.protocol.transaction.TxOut> coins;
                    if (knownSpentOutputs != null) {
                        coins = knownSpentOutputs;
                    } else {
                        coins = transaction.inputs().stream().map(txIn -> {
                            var coin = utxoView.find(txIn.previousOutput()).orElseThrow(
                                    () -> new TransactionValidationException("Missing Taproot input UTXO"));
                            return new ru.bitcoin.node.protocol.transaction.TxOut(coin.amount(), coin.scriptPubKey());
                        }).toList();
                    }
                    ru.bitcoin.node.script.validation.TaprootValidator.validate(
                            transaction, inputIndex, coins, program.program(), scriptVerifyFlags, input.witness(),mode);
                    return;
                }

                validateNativeWitnessProgram(
                        transaction,
                        inputIndex,
                        input,
                        amount,
                        witnessProgram.get(),
                        scriptVerifyFlags, detailed,mode
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
                throw scriptFailure(ScriptError.WITNESS_MALLEATED_P2SH, "Non-canonical P2SH witness scriptSig", detailed);
            }

            if (wrappedWitnessProgram.isPresent()) {
                if (!ScriptNumber.castToBool(wrappedWitnessProgram.get().program())) {
                    throw scriptFailure(ScriptError.EVAL_FALSE, "Witness redeemScript evaluates to false", detailed);
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
                                        scriptVerifyFlags, ScriptDialect.BITCOIN, detailed,mode
                                );

                if (!outerValid) {
                    throw scriptValidationFailed(
                            inputIndex,
                            input, detailed
                    );
                }

                validateWrappedWitnessProgram(
                        transaction,
                        inputIndex,
                        input,
                        amount,
                        wrappedWitnessProgram.get(),
                        scriptVerifyFlags, detailed,mode
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
                        scriptVerifyFlags, ScriptDialect.BITCOIN, detailed,mode
                );

        if (!valid) {
            throw scriptValidationFailed(
                    inputIndex,
                    input, detailed
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

            throw scriptFailure(ScriptError.WITNESS_UNEXPECTED,
                    "Unexpected witness data for non-witness input "
                            + inputIndex
                            + ": "
                            + input.previousOutput(), detailed
            );
        }
    }
    private static void validateWrappedWitnessProgram(
            Transaction transaction,
            int inputIndex,
            TxIn input,
            long amount,
            WitnessProgram witnessProgram,
            int scriptVerifyFlags, boolean detailed, SignatureCheckMode mode
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
                                        amount,
                                        scriptVerifyFlags, ScriptDialect.BITCOIN, detailed,mode
                                );

                if (!valid) {
                    throw scriptValidationFailed(
                            inputIndex,
                            input, detailed
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
                                        amount,
                                        scriptVerifyFlags, ScriptDialect.BITCOIN, detailed,mode
                                );

                if (!valid) {
                    throw scriptValidationFailed(
                            inputIndex,
                            input, detailed
                    );
                }

                return;
            }

            /*
             * Witness v0 допускает только
             * program length 20 или 32.
             */
            throw scriptFailure(ScriptError.WITNESS_PROGRAM_WRONG_LENGTH,
                    "Invalid wrapped witness v0 program length for input "
                            + inputIndex
                            + ": "
                            + witnessProgram.programLength(), detailed
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

            if (detailed) throw new ScriptVerificationException(ScriptError.DISCOURAGE_UPGRADABLE_WITNESS_PROGRAM);
            throw new ScriptExecutionException("Discouraged upgradable witness program");
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
            long amount,
            WitnessProgram witnessProgram,
            int scriptVerifyFlags, boolean detailed, SignatureCheckMode mode
    ) {

        /*
         * Любой native witness program
         * требует пустой scriptSig.
         *
         * Это относится не только к v0,
         * но и к unknown witness versions.
         */
        if (input.scriptSig().length != 0) {

            throw scriptFailure(ScriptError.WITNESS_MALLEATED,
                    "Native witness program requires empty scriptSig for input "
                            + inputIndex, detailed
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
                                        amount,
                                        scriptVerifyFlags, ScriptDialect.BITCOIN, detailed,mode
                                );

                if (!valid) {
                    throw scriptValidationFailed(
                            inputIndex,
                            input, detailed
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
                                        amount,
                                        scriptVerifyFlags, ScriptDialect.BITCOIN, detailed,mode
                                );

                if (!valid) {
                    throw scriptValidationFailed(
                            inputIndex,
                            input, detailed
                    );
                }

                return;
            }

            throw scriptFailure(ScriptError.WITNESS_PROGRAM_WRONG_LENGTH,
                    "Invalid witness v0 program length for input "
                            + inputIndex
                            + ": "
                            + witnessProgram.programLength(), detailed
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

            if (detailed) throw new ScriptVerificationException(ScriptError.DISCOURAGE_UPGRADABLE_WITNESS_PROGRAM);
            throw new ScriptExecutionException("Discouraged upgradable witness program");
        }

        /*
         * Unknown/upgradable witness version:
         * consensus-valid.
         *
         * Позже v1 будет перенаправлен в
         * BIP341/BIP342 Taproot validation.
         */
    }

    private static RuntimeException
    scriptValidationFailed(
            int inputIndex,
            TxIn input, boolean detailed
    ) {
        return scriptFailure(ScriptError.EVAL_FALSE,
                "Script validation failed for input "
                        + inputIndex
                        + ": "
                        + input.previousOutput(), detailed
        );
    }

    private static RuntimeException scriptFailure(ScriptError error, String message, boolean detailed) {
        return detailed ? new ScriptVerificationException(error, message) : new TransactionValidationException(message);
    }

    public static void validateAll(
            Transaction transaction,
            UtxoView utxoView,
            int scriptVerifyFlags
    ) {
        validateAll(transaction, utxoView, scriptVerifyFlags, false);
    }
    public static void validateAll(Transaction transaction, UtxoView utxoView, int scriptVerifyFlags, boolean detailed) {
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

        // Snapshot every referenced coin before dispatch. Workers never touch a mutable
        // chain/overlay view; this mirrors Core's CScriptCheck queue separation.
        java.util.Map<ru.bitcoin.node.protocol.transaction.OutPoint, UtxoEntry> snapshot = new java.util.HashMap<>();
        for (var input : transaction.inputs()) {
            UtxoEntry coin = utxoView.find(input.previousOutput()).orElseThrow(
                    () -> new TransactionValidationException("Missing or already spent UTXO for script validation: " + input.previousOutput()));
            snapshot.put(input.previousOutput(), coin);
        }
        UtxoView immutableView = point -> java.util.Optional.ofNullable(snapshot.get(point));
        java.util.List<Runnable> checks = new java.util.ArrayList<>(transaction.inputs().size());
        for (int inputIndex = 0; inputIndex < transaction.inputs().size(); inputIndex++) {
            final int index = inputIndex;
            checks.add(() -> {
                try { validate(transaction, index, immutableView, scriptVerifyFlags, detailed); }
                catch (ScriptExecutionException error) {
                    if (detailed) throw new InputScriptValidationException(index, error.code(), error.error());
                    throw error;
                } catch (ScriptParseException error) {
                    if (detailed) throw new InputScriptValidationException(index, error.code(), error.error());
                    throw error;
                }
            });
        }
        ScriptCheckQueue.run(checks);
    }

}
