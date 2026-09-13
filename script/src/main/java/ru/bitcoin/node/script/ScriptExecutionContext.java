package ru.bitcoin.node.script;

import ru.bitcoin.node.protocol.transaction.Transaction;

public record ScriptExecutionContext(
        Transaction transaction,
        int inputIndex,
        byte[] scriptCode,
        int flags,
        long amount,
        SignatureVersion signatureVersion
) {

    public ScriptExecutionContext {

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

        if (amount < 0) {
            throw new IllegalArgumentException(
                    "amount must not be negative"
            );
        }

        if (signatureVersion == null) {
            throw new IllegalArgumentException(
                    "signatureVersion must not be null"
            );
        }

        scriptCode =
                scriptCode.clone();
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