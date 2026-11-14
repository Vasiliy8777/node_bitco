package ru.bitcoin.node.script;

import ru.bitcoin.node.protocol.transaction.Transaction;

public record ScriptExecutionContext(
        Transaction transaction,
        int inputIndex,
        byte[] scriptCode,
        int flags,
        long amount,
        SignatureVersion signatureVersion,
        TaprootExecutionData taprootData,
        ScriptDialect dialect,
        SignatureCheckMode signatureCheckMode
) {

    public ScriptExecutionContext {
        java.util.Objects.requireNonNull(signatureCheckMode, "signatureCheckMode");
        java.util.Objects.requireNonNull(dialect, "dialect");
        if (dialect == ScriptDialect.RAVENCOIN && signatureVersion == SignatureVersion.TAPSCRIPT)
            throw new IllegalArgumentException("RVN tapscript is not supported");

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

        if (scriptCode == null) {
            throw new IllegalArgumentException(
                    "scriptCode must not be null"
            );
        }

        // CAmount may carry Core's missing-data sentinel during offline verification.
        // The signature checker tests availability when a signature opcode executes.
        if (signatureVersion == null) {
            throw new IllegalArgumentException(
                    "signatureVersion must not be null"
            );
        }

        scriptCode =
                scriptCode.clone();
    }

    public ScriptExecutionContext(Transaction transaction, int inputIndex, byte[] scriptCode,
                                  int flags, long amount, SignatureVersion signatureVersion, TaprootExecutionData taprootData,
                                  ScriptDialect dialect) {
        this(transaction,inputIndex,scriptCode,flags,amount,signatureVersion,taprootData,dialect,SignatureCheckMode.VERIFY);
    }

    public ScriptExecutionContext(Transaction transaction, int inputIndex, byte[] scriptCode,
                                  int flags, long amount, SignatureVersion signatureVersion, TaprootExecutionData taprootData) {
        this(transaction, inputIndex, scriptCode, flags, amount, signatureVersion, taprootData, ScriptDialect.BITCOIN);
    }

    public ScriptExecutionContext withDialect(ScriptDialect dialect) {
        return new ScriptExecutionContext(transaction, inputIndex, scriptCode, flags, amount, signatureVersion, taprootData, dialect,signatureCheckMode);
    }

    public ScriptExecutionContext withSignatureCheckMode(SignatureCheckMode mode) {
        if (mode == signatureCheckMode) return this;
        return new ScriptExecutionContext(transaction,inputIndex,scriptCode,flags,amount,signatureVersion,taprootData,dialect,mode);
    }

    public ScriptExecutionContext(Transaction transaction, int inputIndex, byte[] scriptCode,
                                  int flags, long amount, SignatureVersion signatureVersion) {
        this(transaction, inputIndex, scriptCode, flags, amount, signatureVersion, null);
    }

    public ScriptExecutionContext(
            Transaction transaction,
            int inputIndex,
            byte[] scriptCode,
            int flags
    ) {
        this(
                transaction,
                inputIndex,
                scriptCode,
                flags,
                0L,
                SignatureVersion.LEGACY
        );
    }

    public ScriptExecutionContext(
            Transaction transaction,
            int inputIndex,
            byte[] scriptCode
    ) {
        this(
                transaction,
                inputIndex,
                scriptCode,
                ScriptVerifyFlags.NONE,
                0L,
                SignatureVersion.LEGACY
        );
    }

    @Override
    public byte[] scriptCode() {
        return scriptCode.clone();
    }
}
