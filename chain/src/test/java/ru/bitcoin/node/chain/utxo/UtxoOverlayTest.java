package ru.bitcoin.node.chain.utxo;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.utxo.CreatedUtxo;
import ru.bitcoin.node.storage.utxo.StoredUtxo;
import ru.bitcoin.node.storage.utxo.UtxoChanges;
import ru.bitcoin.node.storage.utxo.UtxoStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class UtxoOverlayTest {

    @Test
    void shouldReadUtxoFromBaseStore() {

        InMemoryUtxoStore base =
                new InMemoryUtxoStore();

        OutPoint outPoint =
                outPoint('a', 0);

        StoredUtxo utxo =
                utxo(50_000L);

        base.save(
                outPoint,
                utxo
        );

        UtxoOverlay overlay =
                new UtxoOverlay(base);

        assertEquals(
                utxo,
                overlay.find(outPoint)
                        .orElseThrow()
        );
    }

    @Test
    void shouldSeeCreatedUtxoImmediately() {

        InMemoryUtxoStore base =
                new InMemoryUtxoStore();

        UtxoOverlay overlay =
                new UtxoOverlay(base);

        OutPoint outPoint =
                outPoint('b', 0);

        StoredUtxo utxo =
                utxo(40_000L);

        overlay.put(
                outPoint,
                utxo
        );

        /*
         * В RocksDB его ещё нет.
         */
        assertTrue(
                base.find(outPoint)
                        .isEmpty()
        );

        /*
         * Но следующая транзакция блока
         * уже может его увидеть.
         */
        assertEquals(
                utxo,
                overlay.find(outPoint)
                        .orElseThrow()
        );
    }

    @Test
    void shouldReturnSpentUtxoForUndo() {

        InMemoryUtxoStore base =
                new InMemoryUtxoStore();

        OutPoint outPoint =
                outPoint('c', 0);

        StoredUtxo utxo =
                utxo(30_000L);

        base.save(
                outPoint,
                utxo
        );

        UtxoOverlay overlay =
                new UtxoOverlay(base);

        StoredUtxo spent =
                overlay.spend(outPoint);

        assertEquals(
                utxo,
                spent
        );

        assertTrue(
                overlay.find(outPoint)
                        .isEmpty()
        );

        /*
         * Persistent store пока не менялся.
         */
        assertEquals(
                utxo,
                base.find(outPoint)
                        .orElseThrow()
        );
    }

    @Test
    void shouldRejectDoubleSpendInsideOverlay() {

        InMemoryUtxoStore base =
                new InMemoryUtxoStore();

        OutPoint outPoint =
                outPoint('d', 0);

        base.save(
                outPoint,
                utxo(20_000L)
        );

        UtxoOverlay overlay =
                new UtxoOverlay(base);

        overlay.spend(outPoint);

        assertThrows(
                IllegalStateException.class,
                () ->
                        overlay.spend(outPoint)
        );
    }

    @Test
    void shouldProduceSpentPersistentUtxoChange() {

        InMemoryUtxoStore base =
                new InMemoryUtxoStore();

        OutPoint outPoint =
                outPoint('e', 0);

        base.save(
                outPoint,
                utxo(10_000L)
        );

        UtxoOverlay overlay =
                new UtxoOverlay(base);

        overlay.spend(outPoint);

        UtxoChanges changes =
                overlay.changes();

        assertEquals(
                1,
                changes.spentOutputs()
                        .size()
        );

        assertEquals(
                outPoint,
                changes.spentOutputs()
                        .getFirst()
        );

        assertTrue(
                changes.createdOutputs()
                        .isEmpty()
        );
    }

    @Test
    void shouldProduceCreatedPersistentUtxoChange() {

        InMemoryUtxoStore base =
                new InMemoryUtxoStore();

        OutPoint outPoint =
                outPoint('f', 0);

        StoredUtxo utxo =
                utxo(15_000L);

        UtxoOverlay overlay =
                new UtxoOverlay(base);

        overlay.put(
                outPoint,
                utxo
        );

        UtxoChanges changes =
                overlay.changes();

        assertTrue(
                changes.spentOutputs()
                        .isEmpty()
        );

        assertEquals(
                1,
                changes.createdOutputs()
                        .size()
        );

        CreatedUtxo created =
                changes.createdOutputs()
                        .getFirst();

        assertEquals(
                outPoint,
                created.outPoint()
        );

        assertEquals(
                utxo,
                created.utxo()
        );
    }

    @Test
    void shouldCancelOutputCreatedAndSpentInsideSameBlock() {

        InMemoryUtxoStore base =
                new InMemoryUtxoStore();

        OutPoint outPoint =
                outPoint('a', 1);

        StoredUtxo utxo =
                utxo(25_000L);

        UtxoOverlay overlay =
                new UtxoOverlay(base);

        /*
         * TX1 creates A:1
         */
        overlay.put(
                outPoint,
                utxo
        );

        /*
         * TX2 этого же блока spends A:1
         */
        StoredUtxo spent =
                overlay.spend(outPoint);

        assertEquals(
                utxo,
                spent
        );

        UtxoChanges changes =
                overlay.changes();

        /*
         * До блока A:1 не существовал.
         * После блока A:1 не существует.
         *
         * Значит RocksDB вообще менять не нужно.
         */
        assertTrue(
                changes.spentOutputs()
                        .isEmpty()
        );

        assertTrue(
                changes.createdOutputs()
                        .isEmpty()
        );
    }

    @Test
    void shouldProduceReplacementAsSinglePut() {

        InMemoryUtxoStore base =
                new InMemoryUtxoStore();

        OutPoint outPoint =
                outPoint('b', 2);

        StoredUtxo original =
                utxo(50_000L);

        StoredUtxo replacement =
                utxo(35_000L);

        base.save(
                outPoint,
                original
        );

        UtxoOverlay overlay =
                new UtxoOverlay(base);

        /*
         * Сначала существующий UTXO потрачен.
         */
        assertEquals(
                original,
                overlay.spend(outPoint)
        );

        /*
         * Затем тот же OutPoint снова получает
         * итоговое состояние.
         */
        overlay.put(
                outPoint,
                replacement
        );

        UtxoChanges changes =
                overlay.changes();

        /*
         * Итоговая DB операция — только PUT.
         *
         * PUT заменит предыдущее значение.
         */
        assertTrue(
                changes.spentOutputs()
                        .isEmpty()
        );

        assertEquals(
                1,
                changes.createdOutputs()
                        .size()
        );

        assertEquals(
                outPoint,
                changes.createdOutputs()
                        .getFirst()
                        .outPoint()
        );

        assertEquals(
                replacement,
                changes.createdOutputs()
                        .getFirst()
                        .utxo()
        );
    }

    @Test
    void shouldProduceNoChangesWhenFinalStateEqualsOriginal() {

        InMemoryUtxoStore base =
                new InMemoryUtxoStore();

        OutPoint outPoint =
                outPoint('c', 3);

        StoredUtxo original =
                utxo(60_000L);

        base.save(
                outPoint,
                original
        );

        UtxoOverlay overlay =
                new UtxoOverlay(base);

        overlay.spend(outPoint);

        overlay.put(
                outPoint,
                original
        );

        UtxoChanges changes =
                overlay.changes();

        assertTrue(
                changes.spentOutputs()
                        .isEmpty()
        );

        assertTrue(
                changes.createdOutputs()
                        .isEmpty()
        );
    }

    private static StoredUtxo utxo(
            long amount
    ) {
        return new StoredUtxo(
                amount,
                new byte[]{0x51},
                100L,
                false
        );
    }

    private static OutPoint outPoint(
            char hexCharacter,
            long index
    ) {
        return new OutPoint(
                Hash256.fromDisplayHex(
                        String.valueOf(
                                hexCharacter
                        ).repeat(64)
                ),
                new UInt32(index)
        );
    }

    private static final class InMemoryUtxoStore
            implements UtxoStore {

        private final Map<OutPoint, StoredUtxo>
                entries = new HashMap<>();

        @Override
        public void save(
                OutPoint outPoint,
                StoredUtxo utxo
        ) {
            entries.put(
                    outPoint,
                    utxo
            );
        }

        @Override
        public Optional<StoredUtxo> find(
                OutPoint outPoint
        ) {
            return Optional.ofNullable(
                    entries.get(outPoint)
            );
        }

        @Override
        public void delete(
                OutPoint outPoint
        ) {
            entries.remove(outPoint);
        }
    }
    @Test
    void shouldApplyChangesToOverlay() {

        InMemoryUtxoStore base =
                new InMemoryUtxoStore();

        OutPoint oldOutPoint =
                outPoint('d', 4);

        OutPoint newOutPoint =
                outPoint('e', 5);

        StoredUtxo oldUtxo =
                utxo(50_000L);

        StoredUtxo newUtxo =
                utxo(40_000L);

        base.save(
                oldOutPoint,
                oldUtxo
        );

        UtxoOverlay overlay =
                new UtxoOverlay(base);

        UtxoChanges changes =
                new UtxoChanges(
                        List.of(
                                oldOutPoint
                        ),
                        List.of(
                                new CreatedUtxo(
                                        newOutPoint,
                                        newUtxo
                                )
                        )
                );

        overlay.apply(changes);

        assertTrue(
                overlay.find(oldOutPoint)
                        .isEmpty()
        );

        assertEquals(
                newUtxo,
                overlay.find(newOutPoint)
                        .orElseThrow()
        );

        /*
         * Базовый store при этом не изменился.
         */
        assertEquals(
                oldUtxo,
                base.find(oldOutPoint)
                        .orElseThrow()
        );

        assertTrue(
                base.find(newOutPoint)
                        .isEmpty()
        );
    }
    @Test
    void shouldApplyRestoreBeforeDeleteForSameOutPoint() {

        InMemoryUtxoStore base =
                new InMemoryUtxoStore();

        OutPoint intermediate =
                outPoint('f', 6);

        StoredUtxo intermediateUtxo =
                utxo(25_000L);

        UtxoOverlay overlay =
                new UtxoOverlay(base);

        /*
         * Rollback changes:
         *
         * restore intermediate
         * затем удалить intermediate.
         */
        UtxoChanges rollback =
                new UtxoChanges(
                        List.of(
                                intermediate
                        ),
                        List.of(
                                new CreatedUtxo(
                                        intermediate,
                                        intermediateUtxo
                                )
                        )
                );

        overlay.apply(rollback);

        assertTrue(
                overlay.find(intermediate)
                        .isEmpty()
        );

        /*
         * И итоговый persistent delta тоже пуст:
         *
         * до transition его не было,
         * после transition его нет.
         */
        UtxoChanges finalChanges =
                overlay.changes();

        assertTrue(
                finalChanges.spentOutputs()
                        .isEmpty()
        );

        assertTrue(
                finalChanges.createdOutputs()
                        .isEmpty()
        );
    }
}