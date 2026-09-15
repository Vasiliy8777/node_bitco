package ru.bitcoin.node.chain;

import java.util.HashMap;
import java.util.Map;

public final class BlockIndexMedianTimePastResolver implements ru.bitcoin.node.chain.utxo.CoinMedianTimePastResolver {

    private final BlockIndex candidateBlock;
    private final BlockIndexLookup blockIndexLookup;

    /*
     * В одном блоке огромное количество inputs может
     * ссылаться на outputs одинаковой высоты.
     *
     * Нет смысла каждый раз повторно проходить цепочку
     * и пересчитывать один и тот же MTP.
     */
    private final Map<Long, Long> cache =
            new HashMap<>();

    public BlockIndexMedianTimePastResolver(
            BlockIndex candidateBlock,
            BlockIndexLookup blockIndexLookup
    ) {
        if (candidateBlock == null) {
            throw new IllegalArgumentException(
                    "candidateBlock must not be null"
            );
        }

        if (blockIndexLookup == null) {
            throw new IllegalArgumentException(
                    "blockIndexLookup must not be null"
            );
        }

        this.candidateBlock =
                candidateBlock;

        this.blockIndexLookup =
                blockIndexLookup;
    }

    /**
     * Возвращает MTP блока:
     *
     * max(coinHeight - 1, 0)
     *
     * относительно именно той candidate chain,
     * блок которой сейчас валидируется.
     *
     * Это соответствует BIP68 / Bitcoin Core
     * CalculateSequenceLocks().
     */
    @Override
    public long resolvePreviousMedianTimePast(
            long coinHeight
    ) {
        if (coinHeight < 0) {
            throw new IllegalArgumentException(
                    "coinHeight must not be negative"
            );
        }

        if (coinHeight
                > candidateBlock.height()) {

            throw new IllegalArgumentException(
                    "Coin height is above candidate block height. "
                            + "coinHeight="
                            + coinHeight
                            + ", candidateHeight="
                            + candidateBlock.height()
            );
        }

        long targetHeight =
                Math.max(
                        coinHeight - 1L,
                        0L
                );

        return cache.computeIfAbsent(
                targetHeight,
                this::calculateMedianTimePast
        );
    }


    private long calculateMedianTimePast(
            long targetHeight
    ) {
        BlockIndex ancestor =
                ancestorAtHeight(
                        targetHeight
                );

        return MedianTimePast.calculate(
                ancestor,
                blockIndexLookup
        );
    }

    private BlockIndex ancestorAtHeight(
            long targetHeight
    ) {
        if (targetHeight < 0) {
            throw new IllegalArgumentException(
                    "targetHeight must not be negative"
            );
        }

        if (targetHeight
                > candidateBlock.height()) {

            throw new IllegalArgumentException(
                    "Target height is above candidate block"
            );
        }

        BlockIndex current =
                candidateBlock;

        while (current.height()
                > targetHeight) {

            BlockIndex parent =
                    blockIndexLookup.find(
                            current.previousBlockHash()
                    );

            if (parent == null) {
                throw new IllegalStateException(
                        "Missing ancestor while resolving "
                                + "BIP68 median time past. "
                                + "Current block: "
                                + current.hash().toDisplayHex()
                                + ", current height: "
                                + current.height()
                                + ", target height: "
                                + targetHeight
                );
            }

            if (!parent.hash().equals(current.previousBlockHash())) {
                throw new IllegalStateException("BlockIndex lookup returned an unrelated ancestor");
            }

            if (parent.height()
                    != current.height() - 1L) {

                throw new IllegalStateException(
                        "Invalid BlockIndex height linkage. "
                                + "Current height: "
                                + current.height()
                                + ", parent height: "
                                + parent.height()
                );
            }

            current = parent;
        }

        if (current.height()
                != targetHeight) {

            throw new IllegalStateException(
                    "Unable to resolve ancestor at height "
                            + targetHeight
            );
        }

        return current;
    }
}
