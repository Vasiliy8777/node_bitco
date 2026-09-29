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
        if (a.height() > b.height()) {
            a = ancestorAtHeight(a, b.height(), lookup);
        }

        if (b.height() > a.height()) {
            b = ancestorAtHeight(b, a.height(), lookup);
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

    private static BlockIndex ancestorAtHeight(
            BlockIndex index,
            long targetHeight,
            BlockIndexLookup lookup
    ) {
        if (lookup instanceof BlockIndexAncestorLookup ancestorLookup) {
            return ancestorLookup.ancestor(index, targetHeight);
        }
        BlockIndex current = index;
        while (current.height() > targetHeight) {
            current = parentOf(current, lookup);
        }
        return current;
    }
}
