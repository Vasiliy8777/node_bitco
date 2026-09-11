package ru.bitcoin.node.storage.undo;

import java.util.List;

public final class BlockUndoData {

    private final List<TransactionUndo> transactions;

    public BlockUndoData(
            List<TransactionUndo> transactions
    ) {
        if (transactions == null) {
            throw new IllegalArgumentException(
                    "transactions must not be null"
            );
        }

        for (TransactionUndo transactionUndo
                : transactions) {

            if (transactionUndo == null) {
                throw new IllegalArgumentException(
                        "transactions must not contain null"
                );
            }
        }

        this.transactions =
                List.copyOf(transactions);
    }

    public List<TransactionUndo> transactions() {
        return transactions;
    }

    @Override
    public boolean equals(Object o) {

        if (this == o) {
            return true;
        }

        if (!(o instanceof BlockUndoData that)) {
            return false;
        }

        return transactions.equals(
                that.transactions
        );
    }

    @Override
    public int hashCode() {
        return transactions.hashCode();
    }
}