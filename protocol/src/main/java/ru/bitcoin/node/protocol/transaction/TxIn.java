package ru.bitcoin.node.protocol.transaction;

import ru.bitcoin.node.common.types.UInt32;

public final class TxIn {

    public static final UInt32 FINAL_SEQUENCE =
            new UInt32(0xFFFF_FFFFL);

    private final OutPoint previousOutput;
    private final byte[] scriptSig;
    private final UInt32 sequence;
    private final Witness witness;

    public TxIn(
            OutPoint previousOutput,
            byte[] scriptSig,
            UInt32 sequence,
            Witness witness
    ) {
        if (previousOutput == null) {
            throw new IllegalArgumentException(
                    "previousOutput must not be null"
            );
        }

        if (scriptSig == null) {
            throw new IllegalArgumentException(
                    "scriptSig must not be null"
            );
        }

        if (sequence == null) {
            throw new IllegalArgumentException(
                    "sequence must not be null"
            );
        }

        if (witness == null) {
            throw new IllegalArgumentException(
                    "witness must not be null"
            );
        }

        this.previousOutput = previousOutput;
        this.scriptSig = scriptSig.clone();
        this.sequence = sequence;
        this.witness = witness;
    }

    public TxIn(
            OutPoint previousOutput,
            byte[] scriptSig,
            UInt32 sequence
    ) {
        this(
                previousOutput,
                scriptSig,
                sequence,
                Witness.EMPTY
        );
    }

    public OutPoint previousOutput() {
        return previousOutput;
    }

    public byte[] scriptSig() {
        return scriptSig.clone();
    }

    public UInt32 sequence() {
        return sequence;
    }

    public Witness witness() {
        return witness;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof TxIn txIn)) {
            return false;
        }

        return previousOutput.equals(
                txIn.previousOutput
        )
                && java.util.Arrays.equals(
                scriptSig,
                txIn.scriptSig
        )
                && sequence.equals(
                txIn.sequence
        )
                && witness.equals(
                txIn.witness
        );
    }

    @Override
    public int hashCode() {
        int result =
                previousOutput.hashCode();

        result =
                31 * result
                        + java.util.Arrays.hashCode(
                        scriptSig
                );

        result =
                31 * result
                        + sequence.hashCode();

        result =
                31 * result
                        + witness.hashCode();

        return result;
    }
}
