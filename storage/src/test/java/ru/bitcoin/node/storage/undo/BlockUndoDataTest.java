package ru.bitcoin.node.storage.undo;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.storage.utxo.StoredUtxo;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlockUndoDataTest {

    @Test
    void shouldPreserveTransactionOrder() {

        TransactionUndo first =
                transactionUndo(
                        10_000L
                );

        TransactionUndo second =
                transactionUndo(
                        20_000L
                );

        TransactionUndo third =
                transactionUndo(
                        30_000L
                );

        BlockUndoData undoData =
                new BlockUndoData(
                        List.of(
                                first,
                                second,
                                third
                        )
                );

        assertEquals(
                3,
                undoData.transactions().size()
        );

        assertEquals(
                first,
                undoData.transactions().get(0)
        );

        assertEquals(
                second,
                undoData.transactions().get(1)
        );

        assertEquals(
                third,
                undoData.transactions().get(2)
        );
    }

    @Test
    void shouldSupportBlockWithoutTransactionUndo() {

        BlockUndoData undoData =
                new BlockUndoData(
                        List.of()
                );

        assertTrue(
                undoData.transactions().isEmpty()
        );
    }

    @Test
    void shouldDefensivelyCopyTransactionList() {

        List<TransactionUndo> transactions =
                new ArrayList<>();

        transactions.add(
                transactionUndo(
                        1000L
                )
        );

        BlockUndoData undoData =
                new BlockUndoData(
                        transactions
                );

        transactions.clear();

        assertEquals(
                1,
                undoData.transactions().size()
        );

        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        undoData.transactions().clear()
        );
    }

    @Test
    void shouldRejectNullTransaction() {

        List<TransactionUndo> transactions =
                new ArrayList<>();

        transactions.add(
                transactionUndo(
                        1000L
                )
        );

        transactions.add(null);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BlockUndoData(
                                transactions
                        )
        );
    }

    private static TransactionUndo transactionUndo(
            long amount
    ) {

        StoredUtxo utxo =
                new StoredUtxo(
                        amount,
                        new byte[]{0x51},
                        100L,
                        false
                );

        return new TransactionUndo(
                List.of(
                        utxo
                )
        );
    }
}