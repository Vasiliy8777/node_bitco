package ru.bitcoin.node.chain.utxo;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;
import ru.bitcoin.node.storage.undo.BlockUndoData;
import ru.bitcoin.node.storage.undo.TransactionUndo;
import ru.bitcoin.node.storage.utxo.StoredUtxo;
import ru.bitcoin.node.storage.utxo.UtxoStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BlockDisconnectApplierTest {

    @Test
    void shouldDisconnectTransactionsInReverseOrder() {

        /*
         * UTXO X существовал ДО подключения блока.
         */
        OutPoint x =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "11".repeat(32)
                        ),
                        new UInt32(0)
                );

        StoredUtxo utxoX =
                new StoredUtxo(
                        5_000L,
                        new byte[]{0x51},
                        99L,
                        false
                );

        /*
         * Coinbase.
         */
        Transaction coinbase =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        OutPoint.coinbase(),
                                        new byte[]{0x01},
                                        TxIn.FINAL_SEQUENCE
                                )
                        ),
                        List.of(
                                new TxOut(
                                        5_000L,
                                        new byte[]{0x51}
                                )
                        ),
                        new UInt32(0)
                );

        OutPoint coinbaseOutput =
                new OutPoint(
                        coinbase.txId(),
                        new UInt32(0)
                );

        StoredUtxo coinbaseUtxo =
                new StoredUtxo(
                        5_000L,
                        new byte[]{0x51},
                        100L,
                        true
                );

        /*
         * TX1:
         *
         * X -> A
         */
        Transaction tx1 =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        x,
                                        new byte[0],
                                        TxIn.FINAL_SEQUENCE
                                )
                        ),
                        List.of(
                                new TxOut(
                                        4_000L,
                                        new byte[]{0x51}
                                )
                        ),
                        new UInt32(0)
                );

        OutPoint a =
                new OutPoint(
                        tx1.txId(),
                        new UInt32(0)
                );

        StoredUtxo utxoA =
                new StoredUtxo(
                        4_000L,
                        new byte[]{0x51},
                        100L,
                        false
                );

        /*
         * TX2:
         *
         * A -> B
         */
        Transaction tx2 =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        a,
                                        new byte[0],
                                        TxIn.FINAL_SEQUENCE
                                )
                        ),
                        List.of(
                                new TxOut(
                                        3_000L,
                                        new byte[]{0x51}
                                )
                        ),
                        new UInt32(0)
                );

        OutPoint b =
                new OutPoint(
                        tx2.txId(),
                        new UInt32(0)
                );

        StoredUtxo utxoB =
                new StoredUtxo(
                        3_000L,
                        new byte[]{0x51},
                        100L,
                        false
                );

        /*
         * Сам BlockHeader здесь не валидируем.
         *
         * Для BlockDisconnectApplier важны только
         * transactions + undo + текущее UTXO state.
         */
        BlockHeader header =
                new BlockHeader(
                        1,
                        Hash256.fromDisplayHex(
                                "22".repeat(32)
                        ),
                        Hash256.fromDisplayHex(
                                "33".repeat(32)
                        ),
                        new UInt32(1_700_000_000L),
                        new UInt32(0x207FFFFFL),
                        new UInt32(1)
                );

        Block block =
                new Block(
                        header,
                        List.of(
                                coinbase,
                                tx1,
                                tx2
                        )
                );

        /*
         * Undo хранится только для НЕ-coinbase транзакций.
         *
         * TX1 потратил X.
         * TX2 потратил A.
         */
        BlockUndoData undoData =
                new BlockUndoData(
                        List.of(
                                new TransactionUndo(
                                        List.of(
                                                utxoX
                                        )
                                ),
                                new TransactionUndo(
                                        List.of(
                                                utxoA
                                        )
                                )
                        )
                );

        /*
         * Это состояние UTXO ПОСЛЕ подключения блока:
         *
         * X уже потрачен.
         * A создан TX1, но потрачен TX2.
         *
         * Остались:
         * coinbase output C
         * B
         */
        InMemoryUtxoStore baseStore =
                new InMemoryUtxoStore();

        baseStore.save(
                coinbaseOutput,
                coinbaseUtxo
        );

        baseStore.save(
                b,
                utxoB
        );

        UtxoOverlay overlay =
                new UtxoOverlay(
                        baseStore
                );

        BlockDisconnectApplier.apply(
                block,
                undoData,
                overlay
        );

        /*
         * X должен быть восстановлен.
         */
        assertEquals(
                Optional.of(utxoX),
                overlay.find(x)
        );

        /*
         * A был временно восстановлен при rollback TX2,
         * затем удалён rollback TX1.
         */
        assertTrue(
                overlay.find(a).isEmpty()
        );

        /*
         * B был создан TX2 и должен исчезнуть.
         */
        assertTrue(
                overlay.find(b).isEmpty()
        );

        /*
         * Coinbase output тоже должен исчезнуть.
         */
        assertTrue(
                overlay.find(
                        coinbaseOutput
                ).isEmpty()
        );
    }

    private static final class InMemoryUtxoStore
            implements UtxoStore {

        private final Map<OutPoint, StoredUtxo> values =
                new HashMap<>();

        @Override
        public void save(
                OutPoint outPoint,
                StoredUtxo utxo
        ) {
            values.put(
                    outPoint,
                    utxo
            );
        }

        @Override
        public Optional<StoredUtxo> find(
                OutPoint outPoint
        ) {
            return Optional.ofNullable(
                    values.get(outPoint)
            );
        }

        @Override
        public void delete(
                OutPoint outPoint
        ) {
            values.remove(outPoint);
        }
    }
}