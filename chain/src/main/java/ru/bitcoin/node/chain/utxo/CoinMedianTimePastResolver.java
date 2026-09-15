package ru.bitcoin.node.chain.utxo;

@FunctionalInterface
public interface CoinMedianTimePastResolver {

    /**
     * Возвращает MTP блока непосредственно ПЕРЕД
     * блоком coinHeight в ветке проверяемого блока.
     * Для coinHeight == 0 возвращает MTP genesis: max(coinHeight - 1, 0).
     *
     * Именно это значение используется BIP68
     * для time-based relative lock.
     */
    long resolvePreviousMedianTimePast(
            long coinHeight
    );
}
