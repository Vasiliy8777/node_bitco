package ru.bitcoin.node.chain;

public final class ChainSelector {

    private ChainSelector() {
    }

    public static BlockIndex selectBest(
            BlockIndex first,
            BlockIndex second
    ) {
        if (first == null) {
            throw new IllegalArgumentException(
                    "first must not be null"
            );
        }

        if (second == null) {
            throw new IllegalArgumentException(
                    "second must not be null"
            );
        }

        int comparison =
                first.chainWork()
                        .compareTo(
                                second.chainWork()
                        );

        if (comparison > 0) {
            return first;
        }

        if (comparison < 0) {
            return second;
        }

        /*
         * При одинаковом chainWork не переключаемся
         * произвольно на другую ветку.
         *
         * Оставляем текущий first.
         */
        return first;
    }
}