package ru.bitcoin.node.storage.undo;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.storage.utxo.StoredUtxo;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransactionUndoTest {

    @Test
    void shouldCreateTransactionUndo() {

        StoredUtxo first =
                utxo(
                        10_000L,
                        100L
                );

        StoredUtxo second =
                utxo(
                        20_000L,
                        200L
                );

        TransactionUndo undo =
                new TransactionUndo(
                        List.of(
                                first,
                                second
                        )
                );

        assertEquals(
                2,
                undo.spentOutputs().size()
        );

        assertEquals(
                first,
                undo.spentOutputs().get(0)
        );

        assertEquals(
                second,
                undo.spentOutputs().get(1)
        );
    }

    @Test
    void shouldSupportTransactionWithNoInputsToRestore() {

        TransactionUndo undo =
                new TransactionUndo(
                        List.of()
                );

        assertTrue(
                undo.spentOutputs().isEmpty()
        );
    }

    @Test
    void shouldRejectNullList() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TransactionUndo(
                                null
                        )
        );
    }

    @Test
    void shouldRejectNullUtxo() {

        List<StoredUtxo> values =
                new ArrayList<>();

        values.add(
                utxo(
                        1000L,
                        1L
                )
        );

        values.add(null);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TransactionUndo(
                                values
                        )
        );
    }

    @Test
    void shouldDefensivelyCopyList() {

        List<StoredUtxo> values =
                new ArrayList<>();

        values.add(
                utxo(
                        1000L,
                        1L
                )
        );

        TransactionUndo undo =
                new TransactionUndo(
                        values
                );

        values.clear();

        assertEquals(
                1,
                undo.spentOutputs().size()
        );

        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        undo.spentOutputs().clear()
        );
    }

    private static StoredUtxo utxo(
            long amount,
            long height
    ) {

        return new StoredUtxo(
                amount,
                new byte[]{0x51},
                height,
                false
        );
    }
}