package ru.bitcoin.node.storage.undo;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.storage.utxo.StoredUtxo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlockUndoDataSerializerTest {

    @Test
    void shouldSerializeAndDeserializeBlockUndoData() {

        TransactionUndo first =
                new TransactionUndo(
                        List.of(
                                utxo(
                                        10_000L,
                                        100L,
                                        false,
                                        (byte) 0x51
                                ),
                                utxo(
                                        20_000L,
                                        200L,
                                        true,
                                        (byte) 0x52
                                )
                        )
                );

        TransactionUndo second =
                new TransactionUndo(
                        List.of(
                                utxo(
                                        30_000L,
                                        300L,
                                        false,
                                        (byte) 0x53
                                )
                        )
                );

        BlockUndoData original =
                new BlockUndoData(
                        List.of(
                                first,
                                second
                        )
                );

        byte[] serialized =
                BlockUndoDataSerializer.serialize(
                        original
                );

        BlockUndoData restored =
                BlockUndoDataSerializer.deserialize(
                        serialized
                );

        assertEquals(
                original,
                restored
        );
    }

    @Test
    void shouldSupportEmptyBlockUndoData() {

        BlockUndoData original =
                new BlockUndoData(
                        List.of()
                );

        BlockUndoData restored =
                BlockUndoDataSerializer.deserialize(
                        BlockUndoDataSerializer.serialize(
                                original
                        )
                );

        assertEquals(
                original,
                restored
        );

        assertTrue(
                restored.transactions().isEmpty()
        );
    }

    @Test
    void shouldSupportTransactionUndoWithoutSpentOutputs() {

        BlockUndoData original =
                new BlockUndoData(
                        List.of(
                                new TransactionUndo(
                                        List.of()
                                )
                        )
                );

        BlockUndoData restored =
                BlockUndoDataSerializer.deserialize(
                        BlockUndoDataSerializer.serialize(
                                original
                        )
                );

        assertEquals(
                original,
                restored
        );

        assertTrue(
                restored.transactions()
                        .get(0)
                        .spentOutputs()
                        .isEmpty()
        );
    }

    @Test
    void shouldPreserveTransactionAndInputOrder() {

        StoredUtxo first =
                utxo(
                        1_000L,
                        10L,
                        false,
                        (byte) 0x51
                );

        StoredUtxo second =
                utxo(
                        2_000L,
                        20L,
                        false,
                        (byte) 0x52
                );

        StoredUtxo third =
                utxo(
                        3_000L,
                        30L,
                        false,
                        (byte) 0x53
                );

        BlockUndoData original =
                new BlockUndoData(
                        List.of(
                                new TransactionUndo(
                                        List.of(
                                                first,
                                                second
                                        )
                                ),
                                new TransactionUndo(
                                        List.of(
                                                third
                                        )
                                )
                        )
                );

        BlockUndoData restored =
                BlockUndoDataSerializer.deserialize(
                        BlockUndoDataSerializer.serialize(
                                original
                        )
                );

        assertEquals(
                first,
                restored.transactions()
                        .get(0)
                        .spentOutputs()
                        .get(0)
        );

        assertEquals(
                second,
                restored.transactions()
                        .get(0)
                        .spentOutputs()
                        .get(1)
        );

        assertEquals(
                third,
                restored.transactions()
                        .get(1)
                        .spentOutputs()
                        .get(0)
        );
    }

    @Test
    void shouldRejectUnknownVersion() {

        BlockUndoData original =
                new BlockUndoData(
                        List.of()
                );

        byte[] serialized =
                BlockUndoDataSerializer.serialize(
                        original
                );

        serialized[0] = 2;

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BlockUndoDataSerializer.deserialize(
                                serialized
                        )
        );
    }

    @Test
    void shouldRejectTruncatedData() {

        BlockUndoData original =
                new BlockUndoData(
                        List.of(
                                new TransactionUndo(
                                        List.of(
                                                utxo(
                                                        1000L,
                                                        1L,
                                                        false,
                                                        (byte) 0x51
                                                )
                                        )
                                )
                        )
                );

        byte[] serialized =
                BlockUndoDataSerializer.serialize(
                        original
                );

        byte[] truncated =
                new byte[
                        serialized.length - 1
                        ];

        System.arraycopy(
                serialized,
                0,
                truncated,
                0,
                truncated.length
        );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BlockUndoDataSerializer.deserialize(
                                truncated
                        )
        );
    }

    @Test
    void shouldRejectTrailingBytes() {

        byte[] serialized =
                BlockUndoDataSerializer.serialize(
                        new BlockUndoData(
                                List.of()
                        )
                );

        byte[] corrupted =
                new byte[
                        serialized.length + 1
                        ];

        System.arraycopy(
                serialized,
                0,
                corrupted,
                0,
                serialized.length
        );

        corrupted[
                corrupted.length - 1
                ] = 0x55;

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BlockUndoDataSerializer.deserialize(
                                corrupted
                        )
        );
    }

    @Test
    void shouldRejectInvalidTransactionCount() {

        byte[] serialized =
                BlockUndoDataSerializer.serialize(
                        new BlockUndoData(
                                List.of()
                        )
                );

        /*
         * transactionCount начинается с offset 1.
         *
         * Записываем Integer.MAX_VALUE.
         * Фактических TransactionUndo после него нет.
         */
        serialized[1] = (byte) 0xFF;
        serialized[2] = (byte) 0xFF;
        serialized[3] = (byte) 0xFF;
        serialized[4] = 0x7F;

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BlockUndoDataSerializer.deserialize(
                                serialized
                        )
        );
    }

    private static StoredUtxo utxo(
            long amount,
            long height,
            boolean coinbase,
            byte scriptByte
    ) {

        return new StoredUtxo(
                amount,
                new byte[]{
                        scriptByte
                },
                height,
                coinbase
        );
    }
}