package ru.bitcoin.node.protocol.block;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.transaction.Transaction;

import java.util.List;

public final class Block {

    private final BlockHeader header;
    private final List<Transaction> transactions;

    public Block(
            BlockHeader header,
            List<Transaction> transactions
    ) {
        if (header == null) {
            throw new IllegalArgumentException(
                    "header must not be null"
            );
        }

        if (transactions == null) {
            throw new IllegalArgumentException(
                    "transactions must not be null"
            );
        }

        if (transactions.stream().anyMatch(
                transaction -> transaction == null
        )) {
            throw new IllegalArgumentException(
                    "transactions must not contain null"
            );
        }

        this.header = header;
        this.transactions =
                List.copyOf(transactions);
    }

    public BlockHeader header() {
        return header;
    }

    public List<Transaction> transactions() {
        return transactions;
    }

    public Hash256 hash() {
        return header.hash();
    }
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof Block block)) {
            return false;
        }

        return header.equals(block.header)
                && transactions.equals(block.transactions);
    }

    @Override
    public int hashCode() {
        int result = header.hashCode();
        result = 31 * result + transactions.hashCode();
        return result;
    }
}