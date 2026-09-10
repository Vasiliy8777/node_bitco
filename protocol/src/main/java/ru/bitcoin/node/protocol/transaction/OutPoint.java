package ru.bitcoin.node.protocol.transaction;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;

import java.util.Arrays;

public final class OutPoint {

    private static final byte[] ZERO_HASH =
            new byte[Hash256.LENGTH];

    public static final UInt32 COINBASE_INDEX =
            new UInt32(0xFFFF_FFFFL);

    private final Hash256 transactionId;
    private final UInt32 outputIndex;

    public OutPoint(
            Hash256 transactionId,
            UInt32 outputIndex
    ) {
        if (transactionId == null) {
            throw new IllegalArgumentException(
                    "transactionId must not be null"
            );
        }

        if (outputIndex == null) {
            throw new IllegalArgumentException(
                    "outputIndex must not be null"
            );
        }

        this.transactionId = transactionId;
        this.outputIndex = outputIndex;
    }

    public static OutPoint coinbase() {
        return new OutPoint(
                new Hash256(ZERO_HASH),
                COINBASE_INDEX
        );
    }

    public Hash256 transactionId() {
        return transactionId;
    }

    public UInt32 outputIndex() {
        return outputIndex;
    }

    public boolean isCoinbase() {
        return Arrays.equals(
                transactionId.bytes(),
                ZERO_HASH
        ) && outputIndex.value() == 0xFFFF_FFFFL;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }

        if (!(object instanceof OutPoint other)) {
            return false;
        }

        return transactionId.equals(other.transactionId)
                && outputIndex.equals(other.outputIndex);
    }

    @Override
    public int hashCode() {
        int result = transactionId.hashCode();
        result = 31 * result + outputIndex.hashCode();
        return result;
    }
}
