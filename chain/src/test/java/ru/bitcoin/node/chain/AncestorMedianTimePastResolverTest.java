package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AncestorMedianTimePastResolverTest {

    @Test
    void shouldResolveMedianTimePastBeforeCoinHeight() {

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        BlockIndex previous = null;

        /*
         * Строим цепочку:
         *
         * height 90  -> timestamp 1000
         * ...
         * height 100 -> timestamp 1010
         *
         * Для coinHeight = 101 требуется MTP
         * блока height = 100.
         *
         * Последние 11 timestamps:
         *
         * 1000 .. 1010
         *
         * median = 1005.
         */
        for (long height = 90;
             height <= 100;
             height++) {

            Hash256 previousHash =
                    previous == null
                            ? zeroHash()
                            : previous.hash();

            BlockIndex current =
                    blockIndex(
                            previousHash,
                            height,
                            1_000L
                                    + (height - 90L),
                            height
                    );

            indexes.put(
                    current.hash(),
                    current
            );

            previous = current;
        }

        BlockIndex candidateBlock =
                blockIndex(
                        previous.hash(),
                        101L,
                        2_000L,
                        10_001L
                );

        indexes.put(
                candidateBlock.hash(),
                candidateBlock
        );

        BlockIndexLookup lookup =
                indexes::get;

        AncestorMedianTimePastResolver resolver =
                new AncestorMedianTimePastResolver(
                        candidateBlock,
                        lookup
                );

        long result =
                resolver.resolveForCoinHeight(
                        101L
                );

        assertEquals(
                1_005L,
                result
        );

    }

    @Test
    void shouldUseCandidateBranchInsteadOfCompetingBranch() {

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        /*
         * Общий предок.
         */
        BlockIndex commonAncestor =
                blockIndex(
                        zeroHash(),
                        89L,
                        500L,
                        1L
                );

        indexes.put(
                commonAncestor.hash(),
                commonAncestor
        );

        /*
         * Candidate branch:
         *
         * 90..100
         *
         * timestamps:
         * 2000..2010
         *
         * MTP = 2005
         */
        BlockIndex candidateParent =
                buildBranch(
                        commonAncestor,
                        90L,
                        100L,
                        2_000L,
                        100L,
                        indexes
                );

        /*
         * Конкурирующая ветка той же высоты.
         *
         * timestamps:
         * 3000..3010
         *
         * MTP = 3005
         */
        BlockIndex activeParent =
                buildBranch(
                        commonAncestor,
                        90L,
                        100L,
                        3_000L,
                        200L,
                        indexes
                );


        /*
         * Проверяем саму тестовую конфигурацию:
         * обе вершины имеют height 100,
         * но это разные блоки.
         */
        assertEquals(
                100L,
                candidateParent.height()
        );

        assertEquals(
                100L,
                activeParent.height()
        );

        assertNotEquals(
                candidateParent.hash(),
                activeParent.hash()
        );

        BlockIndex candidateBlock =
                blockIndex(
                        candidateParent.hash(),
                        101L,
                        4_000L,
                        999L
                );

        indexes.put(
                candidateBlock.hash(),
                candidateBlock
        );

        BlockIndexLookup lookup =
                indexes::get;

        /*
         * Resolver намеренно строится от
         * candidateParent, а НЕ от activeParent.
         */
        AncestorMedianTimePastResolver resolver =
                new AncestorMedianTimePastResolver(
                        candidateBlock,
                        lookup
                );

        long result =
                resolver.resolveForCoinHeight(
                        101L
                );

        /*
         * Должны получить MTP candidate branch.
         *
         * Если бы resolver ошибочно использовал
         * active branch, получили бы 3005.
         */
        assertEquals(
                2_005L,
                result
        );

        assertNotEquals(
                3_005L,
                result
        );
    }

    @Test
    void shouldResolveOlderCoinHeightFromCandidateAncestry() {

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        /*
         * Нам нужна достаточно длинная история,
         * потому что для coinHeight = 96:
         *
         * target = 95
         *
         * а MTP(height 95) использует:
         *
         * height 85..95.
         */
        BlockIndex previous = null;

        for (long height = 85;
             height <= 100;
             height++) {

            Hash256 previousHash =
                    previous == null
                            ? zeroHash()
                            : previous.hash();

            BlockIndex current =
                    blockIndex(
                            previousHash,
                            height,
                            10_000L
                                    + (height - 85L),
                            300L + height
                    );

            indexes.put(
                    current.hash(),
                    current
            );

            previous = current;
        }

        BlockIndex candidateParent =
                previous;

        AncestorMedianTimePastResolver resolver =
                new AncestorMedianTimePastResolver(
                        candidateParent,
                        indexes::get
                );

        /*
         * coinHeight = 96
         *
         * Требуется MTP блока 95.
         *
         * timestamps height 85..95:
         *
         * 10000 .. 10010
         *
         * median = 10005.
         */
        assertEquals(
                10_005L,
                resolver.resolveForCoinHeight(
                        96L
                )
        );
    }

    @Test
    void shouldUseGenesisMedianTimePastForCoinHeightZero() {

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        BlockIndex genesis =
                blockIndex(
                        zeroHash(),
                        0L,
                        1_000L,
                        1L
                );

        indexes.put(
                genesis.hash(),
                genesis
        );

        BlockIndex block1 =
                blockIndex(
                        genesis.hash(),
                        1L,
                        2_000L,
                        2L
                );

        indexes.put(
                block1.hash(),
                block1
        );

        AncestorMedianTimePastResolver resolver =
                new AncestorMedianTimePastResolver(
                        block1,
                        indexes::get
                );

        /*
         * Bitcoin Core:
         *
         * max(coinHeight - 1, 0)
         *
         * coinHeight = 0
         *
         * => targetHeight = 0
         *
         * MTP genesis при единственном timestamp
         * равен timestamp genesis.
         */
        assertEquals(
                1_000L,
                resolver.resolveForCoinHeight(
                        0L
                )
        );
    }

    @Test
    void shouldRejectCoinHeightAboveCandidateChain() {

        BlockIndex candidateParent =
                blockIndex(
                        zeroHash(),
                        100L,
                        1_000L,
                        1L
                );

        Map<Hash256, BlockIndex> indexes =
                Map.of(
                        candidateParent.hash(),
                        candidateParent
                );

        AncestorMedianTimePastResolver resolver =
                new AncestorMedianTimePastResolver(
                        candidateParent,
                        indexes::get
                );

        /*
         * coinHeight 102 означает targetHeight 101,
         * но candidateParent имеет только height 100.
         */
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        resolver.resolveForCoinHeight(
                                102L
                        )
        );
    }

    @Test
    void shouldFailWhenCandidateAncestryIsMissing() {

        BlockIndex block100 =
                blockIndex(
                        Hash256.fromDisplayHex(
                                "11".repeat(32)
                        ),
                        100L,
                        2_000L,
                        1L
                );

        /*
         * Lookup знает только block100,
         * а его parent отсутствует.
         */
        Map<Hash256, BlockIndex> indexes =
                Map.of(
                        block100.hash(),
                        block100
                );

        AncestorMedianTimePastResolver resolver =
                new AncestorMedianTimePastResolver(
                        block100,
                        indexes::get
                );

        assertThrows(
                IllegalStateException.class,
                () ->
                        resolver.resolveForCoinHeight(
                                100L
                        )
        );
    }

    private static BlockIndex buildBranch(
            BlockIndex parent,
            long firstHeight,
            long lastHeight,
            long firstTimestamp,
            long seedBase,
            Map<Hash256, BlockIndex> indexes
    ) {

        BlockIndex previous =
                parent;

        for (long height = firstHeight;
             height <= lastHeight;
             height++) {

            BlockIndex current =
                    blockIndex(
                            previous.hash(),
                            height,
                            firstTimestamp
                                    + (height - firstHeight),
                            seedBase + height
                    );

            indexes.put(
                    current.hash(),
                    current
            );

            previous = current;
        }

        return previous;
    }

    private static BlockIndex blockIndex(
            Hash256 previousHash,
            long height,
            long timestamp,
            long seed
    ) {

        String merkleHex =
                String.format(
                        "%064x",
                        seed
                );

        BlockHeader header =
                new BlockHeader(
                        1,
                        previousHash,
                        Hash256.fromDisplayHex(
                                merkleHex
                        ),
                        new UInt32(timestamp),
                        new UInt32(
                                0x207FFFFFL
                        ),
                        new UInt32(
                                seed & 0xFFFF_FFFFL
                        )
                );

        return new BlockIndex(
                header.hash(),
                header,
                height,
                previousHash,
                BigInteger.valueOf(
                        height + 1L
                )
        );
    }
    @Test
    void shouldUseCandidateParentMtpForOutputCreatedInCurrentBlock() {

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        BlockIndex previous = null;

        for (long height = 90;
             height <= 100;
             height++) {

            Hash256 previousHash =
                    previous == null
                            ? zeroHash()
                            : previous.hash();

            BlockIndex current =
                    blockIndex(
                            previousHash,
                            height,
                            20_000L
                                    + (height - 90L),
                            500L + height
                    );

            indexes.put(
                    current.hash(),
                    current
            );

            previous = current;
        }

        BlockIndex parent100 =
                previous;

        BlockIndex candidate101 =
                blockIndex(
                        parent100.hash(),
                        101L,
                        30_000L,
                        1001L
                );

        indexes.put(
                candidate101.hash(),
                candidate101
        );

        AncestorMedianTimePastResolver resolver =
                new AncestorMedianTimePastResolver(
                        candidate101,
                        indexes::get
                );

        /*
         * Output создан TX1 внутри candidate block 101.
         *
         * TX2 того же блока его тратит.
         *
         * coinHeight = 101
         *
         * target:
         *
         * max(101 - 1, 0) = 100
         *
         * Поэтому используется MTP блока 100.
         *
         * timestamps:
         *
         * 20000 .. 20010
         *
         * median = 20005.
         */
        assertEquals(
                20_005L,
                resolver.resolveForCoinHeight(
                        101L
                )
        );
    }
    private static Hash256 zeroHash() {
        return Hash256.fromDisplayHex(
                "00".repeat(32)
        );
    }
}