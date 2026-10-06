package ru.bitcoin.node.protocol.transaction;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;

import java.util.List;

public final class Transaction {

    private final int version;
    private final List<TxIn> inputs;
    private final List<TxOut> outputs;
    private final UInt32 lockTime;
    // Transaction and its components defensively own their data. Like Core's
    // immutable CTransaction, memoize hashes rather than repeatedly serializing.
    private volatile Hash256 cachedTxId;
    private volatile Hash256 cachedWtxId;

    public Transaction(
            int version,
            List<TxIn> inputs,
            List<TxOut> outputs,
            UInt32 lockTime
    ) {
        if (inputs == null) {
            throw new IllegalArgumentException(
                    "inputs must not be null"
            );
        }

        if (outputs == null) {
            throw new IllegalArgumentException(
                    "outputs must not be null"
            );
        }

        if (lockTime == null) {
            throw new IllegalArgumentException(
                    "lockTime must not be null"
            );
        }

        this.version = version;
        this.inputs = List.copyOf(inputs);
        this.outputs = List.copyOf(outputs);
        this.lockTime = lockTime;
    }

    public int version() {
        return version;
    }

    public List<TxIn> inputs() {
        return inputs;
    }

    public List<TxOut> outputs() {
        return outputs;
    }

    public UInt32 lockTime() {
        return lockTime;
    }

    public boolean hasWitness() {
        return inputs.stream()
                .anyMatch(
                        input -> !input.witness().isEmpty()
                );
    }

    public boolean isCoinbase() {
        return inputs.size() == 1
                && inputs.getFirst()
                .previousOutput()
                .isCoinbase();
    }

    public Hash256 txId() {
        Hash256 hash = cachedTxId;
        if (hash == null) {
            hash = Hash256Digest.hash(TransactionSerializer.serializeLegacy(this));
            cachedTxId = hash;
        }
        return hash;
    }

    public Hash256 wtxId() {
        if (!hasWitness()) {
            return txId();
        }

        Hash256 hash = cachedWtxId;
        if (hash == null) {
            hash = Hash256Digest.hash(TransactionSerializer.serialize(this));
            cachedWtxId = hash;
        }
        return hash;
    }
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof Transaction transaction)) {
            return false;
        }

        return version == transaction.version
                && inputs.equals(transaction.inputs)
                && outputs.equals(transaction.outputs)
                && lockTime.equals(transaction.lockTime);
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(version);
        result = 31 * result + inputs.hashCode();
        result = 31 * result + outputs.hashCode();
        result = 31 * result + lockTime.hashCode();
        return result;
    }
}
