package ru.bitcoin.node.chain.utxo;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.chain.AncestorMedianTimePastResolver;
import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.chain.BlockIndexLookup;
import ru.bitcoin.node.chain.MedianTimePast;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.transaction.SequenceLocks;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockConnectSequenceLocksTest {

    /*
     * coinHeight = 100
     * sequence   = 2
     *
     * BIP68:
     *
     * minimumHeight =
     *     100 + 2 - 1
     *   = 101
     *
     * EvaluateSequenceLocks требует:
     *
     * minimumHeight < blockHeight
     *
     * Для blockHeight = 101:
     *
     * 101 < 101 == false
     *
     * Поэтому блок должен быть отклонён.
     */
    @Test
    void shouldRejectHeightBasedSequenceLockBeforeFirstValidHeight() {

        TestUtxoStore utxoStore =
                new TestUtxoStore();

        OutPoint previousOutput =
                outPoint("11");

        utxoStore.save(
                previousOutput,
                new StoredUtxo(
                        10_000L,
                        new byte[]{0x51},
                        100L,
                        false
                )
        );

        Transaction transaction =
                transaction(
                        previousOutput,
                        2L,
                        9_000L
                );

        Block block =
                block(
                        101L,
                        Hash256.fromDisplayHex(
                                "00".repeat(32)
                        ),
                        1_700_000_000L,
                        List.of(
                                coinbase(101L),
                                transaction
                        )
                );

        AncestorMedianTimePastResolver resolver =
                simpleResolver(
                        block,
                        101L
                );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        BlockConnectChangesBuilder.build(
                                block,
                                101L,
                                1_700_000_000L,
                                0L,
                                utxoStore,
                                NetworkParametersRegistry.regtest(),
                                resolver
                        )
        );
    }

    /*
     * Тот же UTXO:
     *
     * coinHeight = 100
     * sequence   = 2
     *
     * minimumHeight = 101
     *
     * Для candidate block 102:
     *
     * 101 < 102 == true
     *
     * Это первая допустимая высота.
     */
    @Test
    void shouldAcceptHeightBasedSequenceLockAtFirstValidHeight() {

        TestUtxoStore utxoStore =
                new TestUtxoStore();

        OutPoint previousOutput =
                outPoint("22");

        utxoStore.save(
                previousOutput,
                new StoredUtxo(
                        10_000L,
                        new byte[]{0x51},
                        100L,
                        false
                )
        );

        Transaction transaction =
                transaction(
                        previousOutput,
                        2L,
                        9_000L
                );

        Block block =
                block(
                        102L,
                        Hash256.fromDisplayHex(
                                "00".repeat(32)
                        ),
                        1_700_000_000L,
                        List.of(
                                coinbase(102L),
                                transaction
                        )
                );

        AncestorMedianTimePastResolver resolver =
                simpleResolver(
                        block,
                        102L
                );

        assertDoesNotThrow(
                () ->
                        BlockConnectChangesBuilder.build(
                                block,
                                102L,
                                1_700_000_000L,
                                0L,
                                utxoStore,
                                NetworkParametersRegistry.regtest(),
                                resolver
                        )
        );
    }

    /*
     * Проверяем time-based BIP68.
     *
     * UTXO создан на height=95.
     *
     * Для time-based sequence Bitcoin Core использует:
     *
     * MTP(height 94)
     *
     * sequence:
     *
     * TYPE_FLAG | 2
     *
     * relative time:
     *
     * 2 << 9 = 1024 seconds
     *
     * Историю специально делаем с шагом timestamp=100 sec,
     * поэтому MTP parent ещё недостаточно далеко ушёл
     * относительно coin MTP.
     *
     * Блок должен быть отклонён.
     */
    @Test
    void shouldRejectUnsatisfiedTimeBasedSequenceLock() {

        TestUtxoStore utxoStore =
                new TestUtxoStore();

        OutPoint previousOutput =
                outPoint("33");

        utxoStore.save(
                previousOutput,
                new StoredUtxo(
                        10_000L,
                        new byte[]{0x51},
                        95L,
                        false
                )
        );

        long sequence =
                SequenceLocks.SEQUENCE_LOCKTIME_TYPE_FLAG
                        | 2L;

        Transaction transaction =
                transaction(
                        previousOutput,
                        sequence,
                        9_000L
                );

        TestChain chain =
                createChain(
                        80L,
                        100L,
                        10_000L,
                        100L
                );

        BlockIndex parent =
                chain.indexAtHeight(100L);

        long previousMedianTimePast =
                MedianTimePast.calculate(
                        parent,
                        chain.lookup()
                );

        Block block =
                block(
                        101L,
                        parent.hash(),
                        20_000L,
                        List.of(
                                coinbase(101L),
                                transaction
                        )
                );

        BlockIndex candidate =
                new BlockIndex(
                        block.header().hash(),
                        block.header(),
                        101L,
                        parent.hash(),
                        BigInteger.valueOf(102L)
                );

        chain.add(candidate);

        AncestorMedianTimePastResolver resolver =
                new AncestorMedianTimePastResolver(
                        candidate,
                        chain.lookup()
                );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        BlockConnectChangesBuilder.build(
                                block,
                                101L,
                                previousMedianTimePast,
                                previousMedianTimePast,
                                utxoStore,
                                NetworkParametersRegistry.regtest(),
                                resolver
                        )
        );
    }

    /*
     * Теперь timestamps истории разнесены сильнее.
     *
     * coinHeight = 95
     * coin MTP = MTP(94)
     *
     * sequence = TYPE_FLAG | 2
     * relative = 1024 sec
     *
     * MTP parent candidate блока уже больше
     * требуемой границы.
     *
     * Транзакция должна быть принята.
     */
    @Test
    void shouldAcceptSatisfiedTimeBasedSequenceLock() {

        TestUtxoStore utxoStore =
                new TestUtxoStore();

        OutPoint previousOutput =
                outPoint("44");

        utxoStore.save(
                previousOutput,
                new StoredUtxo(
                        10_000L,
                        new byte[]{0x51},
                        95L,
                        false
                )
        );

        long sequence =
                SequenceLocks.SEQUENCE_LOCKTIME_TYPE_FLAG
                        | 2L;

        Transaction transaction =
                transaction(
                        previousOutput,
                        sequence,
                        9_000L
                );

        /*
         * 500 sec между блоками.
         *
         * Разница MTP(100) и MTP(94)
         * получается достаточно большой
         * для relative lock = 1024 sec.
         */
        TestChain chain =
                createChain(
                        80L,
                        100L,
                        10_000L,
                        500L
                );

        BlockIndex parent =
                chain.indexAtHeight(100L);

        long previousMedianTimePast =
                MedianTimePast.calculate(
                        parent,
                        chain.lookup()
                );

        Block block =
                block(
                        101L,
                        parent.hash(),
                        30_000L,
                        List.of(
                                coinbase(101L),
                                transaction
                        )
                );

        BlockIndex candidate =
                new BlockIndex(
                        block.header().hash(),
                        block.header(),
                        101L,
                        parent.hash(),
                        BigInteger.valueOf(102L)
                );

        chain.add(candidate);

        AncestorMedianTimePastResolver resolver =
                new AncestorMedianTimePastResolver(
                        candidate,
                        chain.lookup()
                );

        assertDoesNotThrow(
                () ->
                        BlockConnectChangesBuilder.build(
                                block,
                                101L,
                                previousMedianTimePast,
                                previousMedianTimePast,
                                utxoStore,
                                NetworkParametersRegistry.regtest(),
                                resolver
                        )
        );
    }

    /*
     * Очень важный overlay-сценарий:
     *
     * TX1 создаёт output внутри candidate block 101.
     * StoredUtxo этого output получает height=101.
     *
     * TX2 в том же блоке пытается потратить этот output
     * с time-based relative lock = 1.
     *
     * Для coinHeight=101 resolver должен использовать:
     *
     * max(101 - 1, 0) = 100
     *
     * то есть MTP candidate parent.
     *
     * sequence=1 time-unit означает 512 sec.
     *
     * minimumTime =
     * parentMTP + 512 - 1
     *
     * Candidate block также оценивается против parentMTP,
     * поэтому lock очевидно ещё не выполнен.
     */
    @Test
    void shouldUseCandidateParentMtpForOutputCreatedInSameBlock() {

        TestUtxoStore utxoStore =
                new TestUtxoStore();

        OutPoint originalOutput =
                outPoint("55");

        utxoStore.save(
                originalOutput,
                new StoredUtxo(
                        10_000L,
                        new byte[]{0x51},
                        90L,
                        false
                )
        );

        Transaction firstTransaction =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        originalOutput,
                                        new byte[0],
                                        TxIn.FINAL_SEQUENCE
                                )
                        ),
                        List.of(
                                new TxOut(
                                        9_000L,
                                        new byte[]{0x51}
                                )
                        ),
                        new UInt32(0)
                );

        OutPoint outputCreatedInCurrentBlock =
                new OutPoint(
                        firstTransaction.txId(),
                        new UInt32(0)
                );

        long sequence =
                SequenceLocks.SEQUENCE_LOCKTIME_TYPE_FLAG
                        | 1L;

        Transaction secondTransaction =
                transaction(
                        outputCreatedInCurrentBlock,
                        sequence,
                        8_000L
                );

        TestChain chain =
                createChain(
                        80L,
                        100L,
                        50_000L,
                        500L
                );

        BlockIndex parent =
                chain.indexAtHeight(100L);

        long previousMedianTimePast =
                MedianTimePast.calculate(
                        parent,
                        chain.lookup()
                );

        Block block =
                block(
                        101L,
                        parent.hash(),
                        60_000L,
                        List.of(
                                coinbase(101L),
                                firstTransaction,
                                secondTransaction
                        )
                );

        BlockIndex candidate =
                new BlockIndex(
                        block.header().hash(),
                        block.header(),
                        101L,
                        parent.hash(),
                        BigInteger.valueOf(102L)
                );

        chain.add(candidate);

        AncestorMedianTimePastResolver resolver =
                new AncestorMedianTimePastResolver(
                        candidate,
                        chain.lookup()
                );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        BlockConnectChangesBuilder.build(
                                block,
                                101L,
                                previousMedianTimePast,
                                previousMedianTimePast,
                                utxoStore,
                                NetworkParametersRegistry.regtest(),
                                resolver
                        )
        );
    }

    private static Transaction transaction(
            OutPoint previousOutput,
            long sequence,
            long outputValue
    ) {
        return new Transaction(
                2,
                List.of(
                        new TxIn(
                                previousOutput,
                                new byte[0],
                                new UInt32(sequence)
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

    private static Transaction coinbase(
            long blockHeight
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
                                5_000L,
                                new byte[]{0x51}
                        )
                ),
                new UInt32(0)
        );
    }

    private static Block block(
            long height,
            Hash256 previousBlockHash,
            long timestamp,
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
                        previousBlockHash,
                        merkleRoot,
                        new UInt32(timestamp),
                        new UInt32(
                                0x207fffffL
                        ),
                        new UInt32(
                                height
                        )
                );

        return new Block(
                header,
                transactions
        );
    }

    /*
     * Для height-based BIP68 MTP вообще не требуется.
     *
     * Поэтому здесь достаточно candidate index без ancestry.
     */
    private static AncestorMedianTimePastResolver simpleResolver(
            Block block,
            long blockHeight
    ) {
        BlockIndex candidate =
                new BlockIndex(
                        block.header().hash(),
                        block.header(),
                        blockHeight,
                        block.header()
                                .previousBlockHash(),
                        BigInteger.ONE
                );

        return new AncestorMedianTimePastResolver(
                candidate,
                hash -> null
        );
    }

    /*
     * Создаёт связанную цепочку BlockIndex:
     *
     * fromHeight -> ... -> toHeight
     */
    private static TestChain createChain(
            long fromHeight,
            long toHeight,
            long firstTimestamp,
            long timestampStep
    ) {
        if (fromHeight < 0) {
            throw new IllegalArgumentException(
                    "fromHeight must not be negative"
            );
        }

        if (toHeight < fromHeight) {
            throw new IllegalArgumentException(
                    "toHeight must be >= fromHeight"
            );
        }

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        Map<Long, BlockIndex> byHeight =
                new HashMap<>();

        Hash256 previousHash =
                Hash256.fromDisplayHex(
                        "00".repeat(32)
                );

        long chainWork = 1L;

        for (long height = fromHeight;
             height <= toHeight;
             height++) {

            long timestamp =
                    firstTimestamp
                            + (height - fromHeight)
                            * timestampStep;

            BlockHeader header =
                    new BlockHeader(
                            1,
                            previousHash,
                            Hash256.fromDisplayHex(
                                    "00".repeat(32)
                            ),
                            new UInt32(timestamp),
                            new UInt32(
                                    0x207fffffL
                            ),
                            new UInt32(
                                    height
                            )
                    );

            BlockIndex index =
                    new BlockIndex(
                            header.hash(),
                            header,
                            height,
                            previousHash,
                            BigInteger.valueOf(
                                    chainWork++
                            )
                    );

            indexes.put(
                    index.hash(),
                    index
            );

            byHeight.put(
                    height,
                    index
            );

            previousHash =
                    index.hash();
        }

        return new TestChain(
                indexes,
                byHeight
        );
    }

    private static OutPoint outPoint(
            String byteHex
    ) {
        return new OutPoint(
                Hash256.fromDisplayHex(
                        byteHex.repeat(32)
                ),
                new UInt32(0)
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

    private static final class TestChain {

        private final Map<Hash256, BlockIndex> indexes;
        private final Map<Long, BlockIndex> byHeight;

        private TestChain(
                Map<Hash256, BlockIndex> indexes,
                Map<Long, BlockIndex> byHeight
        ) {
            this.indexes = indexes;
            this.byHeight = byHeight;
        }

        BlockIndexLookup lookup() {
            return indexes::get;
        }

        BlockIndex indexAtHeight(
                long height
        ) {
            BlockIndex index =
                    byHeight.get(
                            height
                    );

            if (index == null) {
                throw new IllegalStateException(
                        "Missing test BlockIndex at height "
                                + height
                );
            }

            return index;
        }

        void add(
                BlockIndex index
        ) {
            indexes.put(
                    index.hash(),
                    index
            );

            byHeight.put(
                    index.height(),
                    index
            );
        }
    }

    private static final class TestUtxoStore
            implements UtxoStore {

        private final Map<OutPoint, StoredUtxo>
                storage =
                new HashMap<>();

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