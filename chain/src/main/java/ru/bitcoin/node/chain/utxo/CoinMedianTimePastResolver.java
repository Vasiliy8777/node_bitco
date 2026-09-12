package ru.bitcoin.node.chain.utxo;

@FunctionalInterface
public interface CoinMedianTimePastResolver {

    /**
     * Возвращает MTP блока непосредственно ПЕРЕД
     * блоком coinHeight.
     *
     * Именно это значение используется BIP68
     * для time-based relative lock.
     */
    long resolvePreviousMedianTimePast(
            long coinHeight
    );
}