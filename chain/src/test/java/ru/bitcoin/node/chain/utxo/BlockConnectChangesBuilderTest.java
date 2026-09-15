package ru.bitcoin.node.chain.utxo;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.chain.AncestorMedianTimePastResolver;
import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.block.BlockValidationException;
import ru.bitcoin.node.consensus.money.Money;
import ru.bitcoin.node.consensus.transaction.TransactionValidationException;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;
import ru.bitcoin.node.storage.utxo.StoredUtxo;
import ru.bitcoin.node.storage.utxo.UtxoStore;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BlockConnectChangesBuilderTest {
    @Test
    void rejectsNonFinalCoinbase() {
        var original = coinbase(101);
        var tx = new Transaction(original.version(),
                List.of(new TxIn(OutPoint.coinbase(), original.inputs().getFirst().scriptSig(), new UInt32(0))),
                original.outputs(), new UInt32(101));
        assertThrows(TransactionValidationException.class,
                () -> buildBlock(block(List.of(tx)), 101, 1_700_000_000L, new TestUtxoStore()));
    }

    @Test
    void allowsFinalSequenceToDisableCoinbaseLocktime() {
        var original = coinbase(101);
        var tx = new Transaction(original.version(), original.inputs(), original.outputs(), new UInt32(999));
        assertDoesNotThrow(() -> buildBlock(block(List.of(tx)), 101, 1_700_000_000L, new TestUtxoStore()));
    }

    @Test
    void rejectsUnsignedVersionSpendingImmatureRelativeLock() {
        var store = new TestUtxoStore();
        var point = new OutPoint(Hash256.fromDisplayHex("77".repeat(32)), new UInt32(0));
        store.save(point, new StoredUtxo(10_000, new byte[]{0x51}, 100, false));
        var tx = new Transaction(0x80000002,
                List.of(new TxIn(point, new byte[0], new UInt32(2))),
                List.of(new TxOut(9000, new byte[]{0x51})), new UInt32(0));
        assertThrows(TransactionValidationException.class,
                () -> buildBlock(block(List.of(coinbase(101), tx)), 101, 1_700_000_000L, store));
        assertTrue(store.find(point).isPresent());
    }

    @Test
    void rejectsCoinbaseWitnessWithoutCommitment() {
        var original = coinbase(101);
        var tx = new Transaction(1, List.of(new TxIn(OutPoint.coinbase(),
                original.inputs().getFirst().scriptSig(), TxIn.FINAL_SEQUENCE,
                new ru.bitcoin.node.protocol.transaction.Witness(List.of(new byte[32])))),
                original.outputs(), new UInt32(0));
        assertThrows(BlockValidationException.class,
                () -> buildBlock(block(List.of(tx)), 101, 1_700_000_000L, new TestUtxoStore()));
    }

    @Test
    void rejectsExcessSigopsInUnexecutedCoinbaseOutputs() {
        var original = coinbase(101);
        byte[] script = new byte[20_001];
        java.util.Arrays.fill(script, (byte) 0xac);
        var tx = new Transaction(1, original.inputs(), List.of(new TxOut(1, script)), new UInt32(0));
        assertThrows(BlockValidationException.class,
                () -> buildBlock(block(List.of(tx)), 101, 1_700_000_000L, new TestUtxoStore()));
    }

    @Test
    void shouldRejectTransactionSpendingMissingUtxo() {

        UtxoStore utxoStore =
                new TestUtxoStore();

        OutPoint missingOutPoint =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "44".repeat(32)
                        ),
                        new UInt32(0)
                );

        Transaction spendingTransaction =
                regularTransaction(
                        missingOutPoint,
                        1_000L
                );

        Block block =
                block(
                        List.of(
                                coinbase(101),
                                spendingTransaction
                        )
                );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        buildBlock(
                                block,
                                101,
                                1_700_000_000L,
                                utxoStore
                        )
        );
    }

    @Test
    void shouldAllowSpendingOutputCreatedEarlierInSameBlock() {

        TestUtxoStore utxoStore =
                new TestUtxoStore();

        OutPoint originalOutPoint =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "55".repeat(32)
                        ),
                        new UInt32(0)
                );

        utxoStore.save(
                originalOutPoint,
                new StoredUtxo(
                        10_000L,
                        new byte[]{0x51},
                        100,
                        false
                )
        );

        Transaction firstTransaction =
                regularTransaction(
                        originalOutPoint,
                        8_000L
                );

        OutPoint firstTransactionOutput =
                new OutPoint(
                        firstTransaction.txId(),
                        new UInt32(0)
                );

        Transaction secondTransaction =
                regularTransaction(
                        firstTransactionOutput,
                        7_000L
                );

        Block block =
                block(
                        List.of(
                                coinbase(101),
                                firstTransaction,
                                secondTransaction
                        )
                );

        assertDoesNotThrow(
                () ->
                        buildBlock(
                                block,
                                101,
                                1_700_000_000L,
                                utxoStore
                        )
        );
    }

    @Test
    void shouldRejectImmatureCoinbaseUtxoSpend() {

        TestUtxoStore utxoStore =
                new TestUtxoStore();

        OutPoint coinbaseOutPoint =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "66".repeat(32)
                        ),
                        new UInt32(0)
                );

        utxoStore.save(
                coinbaseOutPoint,
                new StoredUtxo(
                        10_000L,
                        new byte[]{0x51},
                        100,
                        true
                )
        );

        Transaction transaction =
                regularTransaction(
                        coinbaseOutPoint,
                        9_000L
                );

        Block block =
                block(
                        List.of(
                                coinbase(199),
                                transaction
                        )
                );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        buildBlock(
                                block,
                                199,
                                1_700_000_000L,
                                utxoStore
                        )
        );
    }

    @Test
    void shouldAcceptCoinbaseEqualToSubsidyPlusFees() {

        TestUtxoStore utxoStore =
                new TestUtxoStore();

        OutPoint previousOutput =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "77".repeat(32)
                        ),
                        new UInt32(0)
                );

        utxoStore.save(
                previousOutput,
                new StoredUtxo(
                        10_000L,
                        new byte[]{0x51},
                        100,
                        false
                )
        );

        Transaction spendingTransaction =
                regularTransaction(
                        previousOutput,
                        7_000L
                );

        long subsidy =
                50L * Money.SATOSHIS_PER_BTC;

        long fee =
                3_000L;

        Block block =
                block(
                        List.of(
                                coinbase(
                                        101,
                                        subsidy + fee
                                ),
                                spendingTransaction
                        )
                );

        assertDoesNotThrow(
                () ->
                        buildBlock(
                                block,
                                101,
                                1_700_000_000L,
                                utxoStore
                        )
        );
    }

    @Test
    void shouldAcceptCoinbaseBelowSubsidyPlusFees() {

        TestUtxoStore utxoStore =
                new TestUtxoStore();

        OutPoint previousOutput =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "88".repeat(32)
                        ),
                        new UInt32(0)
                );

        utxoStore.save(
                previousOutput,
                new StoredUtxo(
                        10_000L,
                        new byte[]{0x51},
                        100,
                        false
                )
        );

        Transaction spendingTransaction =
                regularTransaction(
                        previousOutput,
                        7_000L
                );

        long subsidy =
                50L * Money.SATOSHIS_PER_BTC;

        Block block =
                block(
                        List.of(
                                coinbase(
                                        101,
                                        subsidy
                                ),
                                spendingTransaction
                        )
                );

        assertDoesNotThrow(
                () ->
                        buildBlock(
                                block,
                                101,
                                1_700_000_000L,
                                utxoStore
                        )
        );
    }

    @Test
    void shouldRejectCoinbaseAboveSubsidyPlusFees() {

        TestUtxoStore utxoStore =
                new TestUtxoStore();

        OutPoint previousOutput =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "99".repeat(32)
                        ),
                        new UInt32(0)
                );

        utxoStore.save(
                previousOutput,
                new StoredUtxo(
                        10_000L,
                        new byte[]{0x51},
                        100,
                        false
                )
        );

        Transaction spendingTransaction =
                regularTransaction(
                        previousOutput,
                        7_000L
                );

        long subsidy =
                50L * Money.SATOSHIS_PER_BTC;

        long fee =
                3_000L;

        Block block =
                block(
                        List.of(
                                coinbase(
                                        101,
                                        subsidy
                                                + fee
                                                + 1L
                                ),
                                spendingTransaction
                        )
                );

        assertThrows(
                BlockValidationException.class,
                () ->
                        buildBlock(
                                block,
                                101,
                                1_700_000_000L,
                                utxoStore
                        )
        );
    }

    @Test
    void shouldAccumulateFeesFromTransactionsInSameBlock() {

        TestUtxoStore utxoStore =
                new TestUtxoStore();

        OutPoint originalOutPoint =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "aa".repeat(32)
                        ),
                        new UInt32(0)
                );

        utxoStore.save(
                originalOutPoint,
                new StoredUtxo(
                        10_000L,
                        new byte[]{0x51},
                        100,
                        false
                )
        );

        Transaction firstTransaction =
                regularTransaction(
                        originalOutPoint,
                        8_000L
                );

        OutPoint firstOutput =
                new OutPoint(
                        firstTransaction.txId(),
                        new UInt32(0)
                );

        Transaction secondTransaction =
                regularTransaction(
                        firstOutput,
                        7_000L
                );

        long subsidy =
                50L * Money.SATOSHIS_PER_BTC;

        long totalFees =
                3_000L;

        Block block =
                block(
                        List.of(
                                coinbase(
                                        101,
                                        subsidy
                                                + totalFees
                                ),
                                firstTransaction,
                                secondTransaction
                        )
                );

        assertDoesNotThrow(
                () ->
                        buildBlock(
                                block,
                                101,
                                1_700_000_000L,
                                utxoStore
                        )
        );
    }

    @Test
    void shouldRejectAccumulatedFeesAboveMaxMoney() {

        TestUtxoStore utxoStore =
                new TestUtxoStore();

        OutPoint firstOutPoint =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "ab".repeat(32)
                        ),
                        new UInt32(0)
                );

        OutPoint secondOutPoint =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "cd".repeat(32)
                        ),
                        new UInt32(0)
                );

        long firstInputValue =
                Money.MAX_MONEY;

        long secondInputValue =
                1L;

        utxoStore.save(
                firstOutPoint,
                new StoredUtxo(
                        firstInputValue,
                        new byte[]{0x51},
                        100,
                        false
                )
        );

        utxoStore.save(
                secondOutPoint,
                new StoredUtxo(
                        secondInputValue,
                        new byte[]{0x51},
                        100,
                        false
                )
        );

        Transaction firstTransaction =
                regularTransaction(
                        firstOutPoint,
                        0L
                );

        Transaction secondTransaction =
                regularTransaction(
                        secondOutPoint,
                        0L
                );

        Block block =
                block(
                        List.of(
                                coinbase(
                                        101,
                                        0L
                                ),
                                firstTransaction,
                                secondTransaction
                        )
                );

        assertThrows(
                BlockValidationException.class,
                () ->
                        buildBlock(
                                block,
                                101,
                                1_700_000_000L,
                                utxoStore
                        )
        );
    }

    @Test
    void shouldRejectBip30DuplicateWithUnspentOutput() {

        TestUtxoStore utxoStore =
                new TestUtxoStore();

        Transaction duplicateCoinbase =
                coinbase(
                        101,
                        5_000L
                );

        OutPoint existingOutPoint =
                new OutPoint(
                        duplicateCoinbase.txId(),
                        new UInt32(0)
                );

        utxoStore.save(
                existingOutPoint,
                new StoredUtxo(
                        5_000L,
                        new byte[]{0x51},
                        1,
                        true
                )
        );

        Block block =
                block(
                        List.of(
                                duplicateCoinbase
                        )
                );

        assertThrows(
                BlockValidationException.class,
                () ->
                        buildBlock(
                                block,
                                101,
                                1_700_000_000L,
                                utxoStore
                        )
        );
    }

    @Test
    void shouldAllowBip30DuplicateWhenPreviousTransactionIsFullySpent() {

        TestUtxoStore utxoStore =
                new TestUtxoStore();

        Transaction duplicateCoinbase =
                coinbase(
                        101,
                        5_000L
                );

        /*
         * Никакого UTXO с этим txid больше нет:
         * предыдущая транзакция считается полностью потраченной.
         */
        Block block =
                block(
                        List.of(
                                duplicateCoinbase
                        )
                );

        assertDoesNotThrow(
                () ->
                        buildBlock(
                                block,
                                101,
                                1_700_000_000L,
                                utxoStore
                        )
        );
    }

    @Test
    void shouldRejectBip30WhenAnyPreviousOutputRemainsUnspent() {

        TestUtxoStore utxoStore =
                new TestUtxoStore();

        Transaction duplicate =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        OutPoint.coinbase(),
                                        new byte[]{
                                                0x01,
                                                0x65
                                        },
                                        TxIn.FINAL_SEQUENCE
                                )
                        ),
                        List.of(
                                new TxOut(
                                        2_000L,
                                        new byte[]{0x51}
                                ),
                                new TxOut(
                                        3_000L,
                                        new byte[]{0x51}
                                )
                        ),
                        new UInt32(0)
                );

        /*
         * output #0 уже потрачен,
         * но #1 всё ещё существует.
         */
        utxoStore.save(
                new OutPoint(
                        duplicate.txId(),
                        new UInt32(1)
                ),
                new StoredUtxo(
                        3_000L,
                        new byte[]{0x51},
                        1,
                        true
                )
        );

        Block block =
                block(
                        List.of(
                                duplicate
                        )
                );

        assertThrows(
                BlockValidationException.class,
                () ->
                        buildBlock(
                                block,
                                101,
                                1_700_000_000L,
                                utxoStore
                        )
        );
    }

    @Test
    void shouldRejectBlockWhoseFirstTransactionIsNotCoinbase() {

        TestUtxoStore utxoStore =
                new TestUtxoStore();

        OutPoint previousOutput =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "12".repeat(32)
                        ),
                        new UInt32(0)
                );

        utxoStore.save(
                previousOutput,
                new StoredUtxo(
                        10_000L,
                        new byte[]{0x51},
                        100,
                        false
                )
        );

        Transaction regularTransaction =
                regularTransaction(
                        previousOutput,
                        9_000L
                );

        Block block =
                block(
                        List.of(
                                regularTransaction
                        )
                );

        assertThrows(
                BlockValidationException.class,
                () ->
                        buildBlock(
                                block,
                                101,
                                1_700_000_000L,
                                utxoStore
                        )
        );
    }

    @Test
    void shouldRejectAdditionalCoinbaseTransaction() {

        TestUtxoStore utxoStore =
                new TestUtxoStore();

        Transaction firstCoinbase =
                coinbase(
                        101,
                        5_000L
                );

        Transaction secondCoinbase =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        OutPoint.coinbase(),
                                        new byte[]{
                                                0x02,
                                                0x02
                                        },
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

        Block block =
                block(
                        List.of(
                                firstCoinbase,
                                secondCoinbase
                        )
                );

        assertThrows(
                BlockValidationException.class,
                () ->
                        buildBlock(
                                block,
                                101,
                                1_700_000_000L,
                                utxoStore
                        )
        );
    }

    @Test
    void shouldNotModifyUtxoStoreWhenBlockStructureIsInvalid() {

        TestUtxoStore utxoStore =
                new TestUtxoStore();

        OutPoint previousOutput =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "34".repeat(32)
                        ),
                        new UInt32(0)
                );

        StoredUtxo originalUtxo =
                new StoredUtxo(
                        10_000L,
                        new byte[]{0x51},
                        100,
                        false
                );

        utxoStore.save(
                previousOutput,
                originalUtxo
        );

        Transaction transaction =
                regularTransaction(
                        previousOutput,
                        9_000L
                );

        Block invalidBlock =
                block(
                        List.of(
                                transaction
                        )
                );

        assertThrows(
                BlockValidationException.class,
                () ->
                        buildBlock(
                                invalidBlock,
                                101,
                                1_700_000_000L,
                                utxoStore
                        )
        );

        StoredUtxo stillStored =
                utxoStore
                        .find(previousOutput)
                        .orElseThrow();

        assertEquals(
                10_000L,
                stillStored.amount()
        );

        assertArrayEquals(
                new byte[]{0x51},
                stillStored.scriptPubKey()
        );
    }

    @Test
    void shouldRejectNonFinalTransaction() {

        TestUtxoStore utxoStore =
                new TestUtxoStore();

        OutPoint previousOutput =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "45".repeat(32)
                        ),
                        new UInt32(0)
                );

        utxoStore.save(
                previousOutput,
                new StoredUtxo(
                        10_000L,
                        new byte[]{0x51},
                        100,
                        false
                )
        );

        /*
         * version=1:
         *
         * Этот тест проверяет абсолютный nLockTime,
         * а не BIP68 relative sequence locks.
         */
        Transaction nonFinalTransaction =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        previousOutput,
                                        new byte[0],
                                        new UInt32(0)
                                )
                        ),
                        List.of(
                                new TxOut(
                                        9_000L,
                                        new byte[]{0x51}
                                )
                        ),
                        new UInt32(101)
                );

        Block block =
                block(
                        List.of(
                                coinbase(101),
                                nonFinalTransaction
                        )
                );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        buildBlock(
                                block,
                                101,
                                1_700_000_000L,
                                utxoStore
                        )
        );
    }

    @Test
    void shouldAcceptFinalTransaction() {

        TestUtxoStore utxoStore =
                new TestUtxoStore();

        OutPoint previousOutput =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "46".repeat(32)
                        ),
                        new UInt32(0)
                );

        utxoStore.save(
                previousOutput,
                new StoredUtxo(
                        10_000L,
                        new byte[]{0x51},
                        100,
                        false
                )
        );

        /*
         * version=1:
         *
         * Проверяем только абсолютный nLockTime.
         */
        Transaction finalTransaction =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        previousOutput,
                                        new byte[0],
                                        new UInt32(0)
                                )
                        ),
                        List.of(
                                new TxOut(
                                        9_000L,
                                        new byte[]{0x51}
                                )
                        ),
                        new UInt32(100)
                );

        Block block =
                block(
                        List.of(
                                coinbase(101),
                                finalTransaction
                        )
                );

        assertDoesNotThrow(
                () ->
                        buildBlock(
                                block,
                                101,
                                1_700_000_000L,
                                utxoStore
                        )
        );
    }

    /*
     * Helper для тестов этого класса.
     *
     * Здесь BIP68 отдельно не тестируется.
     * Все обычные транзакции имеют version=1,
     * поэтому SequenceLocks для них не применяются.
     */
    private static BlockConnectChanges buildBlock(
            Block block,
            long blockHeight,
            long lockTimeCutoff,
            UtxoStore utxoStore
    ) {
        BlockIndex candidateBlock =
                new BlockIndex(
                        block.header().hash(),
                        block.header(),
                        blockHeight,
                        block.header().previousBlockHash(),
                        BigInteger.ONE
                );

        AncestorMedianTimePastResolver resolver =
                new AncestorMedianTimePastResolver(
                        candidateBlock,
                        hash -> {
                            if (candidateBlock
                                    .hash()
                                    .equals(hash)) {

                                return candidateBlock;
                            }

                            return null;
                        }
                );

        return BlockConnectChangesBuilder.build(
                block,
                blockHeight,
                lockTimeCutoff,

                /*
                 * Не используется BIP68-транзакциями
                 * в этом тестовом классе.
                 */
                0L,

                utxoStore,
                NetworkParametersRegistry.regtest(),
                resolver
        );
    }

    private static Transaction coinbase(
            long blockHeight
    ) {
        return coinbase(
                blockHeight,
                5_000L
        );
    }

    private static Transaction coinbase(
            long blockHeight,
            long value
    ) {
        return new Transaction(
                1,
                List.of(
                        new TxIn(
                                OutPoint.coinbase(),
                                bip34HeightScriptSig(
                                        blockHeight
                                ),
                                TxIn.FINAL_SEQUENCE
                        )
                ),
                List.of(
                        new TxOut(
                                value,
                                new byte[]{0x51}
                        )
                ),
                new UInt32(0)
        );
    }

    private static Transaction regularTransaction(
            OutPoint previousOutput,
            long outputValue
    ) {
        return new Transaction(
                1,
                List.of(
                        new TxIn(
                                previousOutput,
                                new byte[0],
                                TxIn.FINAL_SEQUENCE
                        )
                ),
                List.of(
                        new TxOut(
                                outputValue,
                                new byte[]{0x51}
                        )
                ),
                new UInt32(0)
        );
    }

    private static Block block(
            List<Transaction> transactions
    ) {

        Hash256 merkleRoot =
                MerkleTree.calculateRoot(
                        transactions.stream()
                                .map(Transaction::txId)
                                .toList()
                );

        BlockHeader header =
                new BlockHeader(
                        1,
                        Hash256.fromDisplayHex(
                                "00".repeat(32)
                        ),
                        merkleRoot,
                        new UInt32(
                                1_700_000_000L
                        ),
                        new UInt32(
                                0x207fffffL
                        ),
                        new UInt32(0)
                );

        return new Block(
                header,
                transactions
        );
    }

    private static byte[] bip34HeightScriptSig(
            long blockHeight
    ) {
        if (blockHeight <= 0) {
            throw new IllegalArgumentException(
                    "blockHeight must be positive"
            );
        }

        /*
         * CScript << n для 1..16 использует OP_1..OP_16.
         *
         * Добавляем второй байт, потому что coinbase
         * scriptSig должен иметь минимум 2 байта.
         */
        if (blockHeight <= 16) {
            return new byte[]{
                    (byte) (
                            0x51
                                    + blockHeight
                                    - 1
                    ),
                    0x00
            };
        }

        List<Byte> bytes =
                new ArrayList<>();

        long value =
                blockHeight;

        while (value != 0) {
            bytes.add(
                    (byte) (
                            value & 0xFF
                    )
            );

            value >>>= 8;
        }

        if ((bytes.get(
                bytes.size() - 1
        ) & 0x80) != 0) {

            bytes.add(
                    (byte) 0x00
            );
        }

        byte[] scriptSig =
                new byte[
                        1 + bytes.size()
                        ];

        scriptSig[0] =
                (byte) bytes.size();

        for (int i = 0;
             i < bytes.size();
             i++) {

            scriptSig[i + 1] =
                    bytes.get(i);
        }

        return scriptSig;
    }

    private static final class TestUtxoStore
            implements UtxoStore {

        private final Map<OutPoint, StoredUtxo>
                storage = new HashMap<>();

        @Override
        public void save(
                OutPoint outPoint,
                StoredUtxo utxo
        ) {
            storage.put(
                    outPoint,
                    utxo
            );
        }

        @Override
        public Optional<StoredUtxo> find(
                OutPoint outPoint
        ) {
            return Optional.ofNullable(
                    storage.get(
                            outPoint
                    )
            );
        }

        @Override
        public void delete(
                OutPoint outPoint
        ) {
            storage.remove(
                    outPoint
            );
        }
    }
}
