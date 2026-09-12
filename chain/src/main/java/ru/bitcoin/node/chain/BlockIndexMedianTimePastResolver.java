/*
package ru.bitcoin.node.chain;

import ru.bitcoin.node.chain.utxo.CoinMedianTimePastResolver;

public final class BlockIndexMedianTimePastResolver
        implements CoinMedianTimePastResolver {

    private final BlockIndexLookup blockIndexLookup;

    public BlockIndexMedianTimePastResolver(
            BlockIndexLookup blockIndexLookup
    ) {
        if (blockIndexLookup == null) {
            throw new IllegalArgumentException(
                    "blockIndexLookup must not be null"
            );
        }

        this.blockIndexLookup =
                blockIndexLookup;
    }

    @Override
    public long resolvePreviousMedianTimePast(
            long coinHeight
    ) {
        if (coinHeight < 0) {
            throw new IllegalArgumentException(
                    "coinHeight must not be negative"
            );
        }

        */
/*
         * Для outputs genesis нет предыдущего блока.
         *
         * Такое значение практически не понадобится
         * BIP68, поскольку BIP68 активирован намного позже,
         * но поведение определяем явно.
         *//*

        if (coinHeight == 0) {
            return 0L;
        }

        BlockIndex coinBlock =
                findByHeight(coinHeight);

        BlockIndex previousBlock =
                blockIndexLookup.find(
                                coinBlock.previousBlockHash()
                        )
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Previous BlockIndex not found "
                                                        + "for coin height "
                                                        + coinHeight
                                        )
                        );

        return MedianTimePast.calculate(
                previousBlock,
                blockIndexLookup
        );
    }

    private BlockIndex findByHeight(
            long height
    ) {
        throw new UnsupportedOperationException(
                "Height lookup must be resolved from candidate-chain context"
        );
    }
}*/
