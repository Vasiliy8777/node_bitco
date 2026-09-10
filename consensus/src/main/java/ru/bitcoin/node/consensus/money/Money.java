package ru.bitcoin.node.consensus.money;

public final class Money {

    public static final long SATOSHIS_PER_BTC =
            100_000_000L;

    public static final long MAX_BTC =
            21_000_000L;

    public static final long MAX_MONEY =
            MAX_BTC * SATOSHIS_PER_BTC;

    private Money() {
    }

    public static boolean isValidAmount(
            long amount
    ) {
        return amount >= 0
                && amount <= MAX_MONEY;
    }
}
