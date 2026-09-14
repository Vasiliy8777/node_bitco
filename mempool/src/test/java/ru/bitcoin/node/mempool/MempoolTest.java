package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.transaction.TransactionWeight;
import ru.bitcoin.node.consensus.transaction.UtxoEntry;
import ru.bitcoin.node.consensus.transaction.UtxoView;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;
import ru.bitcoin.node.protocol.transaction.Witness;
import ru.bitcoin.node.script.Opcode;
import ru.bitcoin.node.script.ScriptExecutionException;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MempoolTest {

    private static final long SPENDING_HEIGHT =
            200L;

    @Test
    void validTransactionMustBeInserted() {

        Fixture fixture =
                createFixture();

        Mempool mempool =
                new Mempool();

        MempoolEntry entry =
                mempool.admit(
                        fixture.transaction(),
                        SPENDING_HEIGHT,
                        fixture.utxoView()
                );

        assertEquals(
                1,
                mempool.size()
        );

        assertFalse(
                mempool.isEmpty()
        );

        assertTrue(
                mempool.contains(
                        fixture.transaction()
                                .txId()
                )
        );

        assertTrue(
                mempool.find(
                                fixture.transaction()
                                        .txId()
                        )
                        .isPresent()
        );

        assertEquals(
                10_000L,
                entry.fee()
        );

        assertEquals(
                TransactionWeight.calculate(
                        fixture.transaction()
                ),
                entry.weight()
        );

        assertTrue(
                TransactionSerializer.serializeLegacy(
                        fixture.transaction()
                ).length
                        >= MempoolPolicy.MIN_STANDARD_TX_NONWITNESS_SIZE
        );

        assertTrue(
                entry.weight() > 0
        );
    }

    @Test
    void duplicateTransactionMustBeRejected() {

        Fixture fixture =
                createFixture();

        Mempool mempool =
                new Mempool();

        mempool.admit(
                fixture.transaction(),
                SPENDING_HEIGHT,
                fixture.utxoView()
        );

        assertThrows(
                MempoolAdmissionException.class,
                () ->
                        mempool.admit(
                                fixture.transaction(),
                                SPENDING_HEIGHT,
                                fixture.utxoView()
                        )
        );

        assertEquals(
                1,
                mempool.size()
        );
    }

    @Test
    void removeMustDeleteTransaction() {

        Fixture fixture =
                createFixture();

        Mempool mempool =
                new Mempool();

        mempool.admit(
                fixture.transaction(),
                SPENDING_HEIGHT,
                fixture.utxoView()
        );

        Hash256 txId =
                fixture.transaction()
                        .txId();

        assertTrue(
                mempool.remove(
                                txId
                        )
                        .isPresent()
        );

        assertFalse(
                mempool.contains(
                        txId
                )
        );

        assertEquals(
                0,
                mempool.size()
        );
    }

    @Test
    void failedValidationMustNotModifyMempool() {

        Fixture fixture =
                createNonStandardWitnessFixture();

        Mempool mempool =
                new Mempool();

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        mempool.admit(
                                fixture.transaction(),
                                SPENDING_HEIGHT,
                                fixture.utxoView()
                        )
        );

        /*
         * Критически важно:
         *
         * transaction не должна попасть
         * в mempool даже частично.
         */
        assertTrue(
                mempool.isEmpty()
        );

        assertEquals(
                0,
                mempool.size()
        );

        assertFalse(
                mempool.contains(
                        fixture.transaction()
                                .txId()
                )
        );
    }

    @Test
    void entriesMustReturnSnapshot() {

        /*
         * Для этого теста используем пустой pool.
         *
         * Нам важно проверить, что наружу
         * не отдаётся mutable internal collection.
         */
        Mempool mempool =
                new Mempool();

        List<MempoolEntry> snapshot =
                mempool.entries();

        assertTrue(
                snapshot.isEmpty()
        );

        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        snapshot.add(
                                null
                        )
        );
    }

    private static Fixture createFixture() {

        /*
         * Здесь используем простой legacy
         * anyone-can-spend script:
         *
         * scriptPubKey = OP_1
         *
         * Чтобы transaction прошла STANDARD
         * script validation.
         */
        byte[] scriptPubKey = {
                (byte) Opcode.OP_1
        };

        byte[] previousHash =
                new byte[32];

        Arrays.fill(
                previousHash,
                (byte) 0x51
        );

        OutPoint previousOutput =
                new OutPoint(
                        new Hash256(
                                previousHash
                        ),
                        new UInt32(0L)
                );

        TxIn input =
                new TxIn(
                        previousOutput,
                        new byte[0],
                        new UInt32(
                                0xffff_fffeL
                        ),
                        Witness.EMPTY
                );

        TxOut output = new TxOut(
                90_000L,
                new byte[]{
                        (byte) Opcode.OP_RETURN,
                        0x04,
                        0x01,
                        0x02,
                        0x03,
                        0x04
                }
        );

        Transaction transaction =
                new Transaction(
                        2,
                        List.of(
                                input
                        ),
                        List.of(
                                output
                        ),
                        new UInt32(0L)
                );

        UtxoEntry utxo =
                new UtxoEntry(
                        100_000L,
                        scriptPubKey,
                        100L,
                        false
                );

        UtxoView view =
                outPoint -> {

                    if (previousOutput.equals(
                            outPoint
                    )) {

                        return Optional.of(
                                utxo
                        );
                    }

                    return Optional.empty();
                };

        return new Fixture(
                transaction,
                view
        );
    }

    private record Fixture(
            Transaction transaction,
            UtxoView utxoView
    ) {
    }
    private static Fixture createNonStandardWitnessFixture() {

        /*
         * Native witness v1 program:
         *
         * OP_1
         * PUSH32
         * <32 bytes>
         *
         * В нашей текущей pre-Taproot реализации:
         *
         * consensus:
         *     forward-compatible -> допустимо
         *
         * STANDARD mempool policy:
         *     DISCOURAGE_UPGRADABLE_WITNESS_PROGRAM
         *     -> reject
         */
        byte[] scriptPubKey =
                new byte[34];

        scriptPubKey[0] =
                (byte) Opcode.OP_1;

        scriptPubKey[1] =
                32;

        Arrays.fill(
                scriptPubKey,
                2,
                scriptPubKey.length,
                (byte) 0x42
        );

        byte[] previousHash =
                new byte[32];

        Arrays.fill(
                previousHash,
                (byte) 0x52
        );

        OutPoint previousOutput =
                new OutPoint(
                        new Hash256(
                                previousHash
                        ),
                        new UInt32(0L)
                );

        /*
         * Для native witness program
         * scriptSig должен быть пустым.
         */
        TxIn input =
                new TxIn(
                        previousOutput,
                        new byte[0],
                        new UInt32(
                                0xffff_fffeL
                        ),
                        Witness.EMPTY
                );

        TxOut output =
                new TxOut(
                        90_000L,
                        new byte[]{
                                (byte) Opcode.OP_1
                        }
                );

        Transaction transaction =
                new Transaction(
                        2,
                        List.of(
                                input
                        ),
                        List.of(
                                output
                        ),
                        new UInt32(0L)
                );

        UtxoEntry utxo =
                new UtxoEntry(
                        100_000L,
                        scriptPubKey,
                        100L,
                        false
                );

        UtxoView view =
                outPoint -> {

                    if (previousOutput.equals(
                            outPoint
                    )) {

                        return Optional.of(
                                utxo
                        );
                    }

                    return Optional.empty();
                };

        return new Fixture(
                transaction,
                view
        );
    }
    @Test
    void transactionBelowMinRelayFeeMustNotEnterMempool() {

        Fixture fixture =
                createFixture();

        /*
         * У нашей fixture:
         *
         * fee = 10_000 sat.
         *
         * Ставим заведомо огромную policy rate,
         * чтобы required fee была выше.
         */
        MempoolPolicy policy =
                new MempoolPolicy(
                        new FeeRate(
                                1_000_000_000L
                        )
                );

        Mempool mempool =
                new Mempool(
                        policy
                );

        assertThrows(
                MempoolAdmissionException.class,
                () ->
                        mempool.admit(
                                fixture.transaction(),
                                SPENDING_HEIGHT,
                                fixture.utxoView()
                        )
        );

        assertTrue(
                mempool.isEmpty()
        );

        assertFalse(
                mempool.contains(
                        fixture.transaction()
                                .txId()
                )
        );
    }
}