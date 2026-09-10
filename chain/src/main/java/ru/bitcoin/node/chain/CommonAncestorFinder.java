package ru.bitcoin.node.chain;

public final class CommonAncestorFinder {

    private CommonAncestorFinder() {
    }

    public static BlockIndex find(
            BlockIndex first,
            BlockIndex second,
            BlockIndexLookup lookup
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

        if (lookup == null) {
            throw new IllegalArgumentException(
                    "lookup must not be null"
            );
        }

        BlockIndex a = first;
        BlockIndex b = second;

        /*
         * Сначала поднимаем более высокую ветку
         * до одинаковой высоты.
         */
        while (a.height() > b.height()) {
            a = parentOf(a, lookup);
        }

        while (b.height() > a.height()) {
            b = parentOf(b, lookup);
        }

        /*
         * Теперь обе вершины находятся
         * на одинаковой высоте.
         *
         * Поднимаемся одновременно,
         * пока не найдём один и тот же блок.
         */
        while (!a.hash().equals(b.hash())) {
            a = parentOf(a, lookup);
            b = parentOf(b, lookup);
        }

        return a;
    }

    private static BlockIndex parentOf(
            BlockIndex index,
            BlockIndexLookup lookup
    ) {
        if (index.height() == 0) {
            throw new IllegalStateException(
                    "Common ancestor not found"
            );
        }

        BlockIndex parent =
                lookup.find(
                        index.previousBlockHash()
                );

        if (parent == null) {
            throw new IllegalStateException(
                    "Parent block index not found: "
                            + index.previousBlockHash()
                            .toDisplayHex()
            );
        }

        return parent;
    }
}