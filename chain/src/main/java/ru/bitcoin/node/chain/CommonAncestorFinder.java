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

        if (a.hash().equals(b.hash())) return a;
        if (lookup instanceof BlockIndexAncestorLookup accelerated) {
            // Branch membership is monotonic with height: below the fork both
            // ancestors match, above it they differ. Never walk a deep fork's
            // parents one by one through persistent storage.
            long low;
            long high = a.height();
            long step = 1;
            BlockIndex common;
            // Bracket the fork first so ordinary shallow reorganizations remain
            // cheap even when the chain tip is hundreds of thousands high.
            while (true) {
                long target = high - Math.min(high, step);
                BlockIndex left = accelerated.ancestor(a, target);
                BlockIndex right = accelerated.ancestor(b, target);
                if (left.hash().equals(right.hash())) {
                    low = target;
                    common = left;
                    break;
                }
                if (target == 0) throw new IllegalStateException("Common ancestor not found");
                high = target;
                step = step > Long.MAX_VALUE / 2 ? high : Math.min(high, step * 2);
            }
            while (high - low > 1) {
                long middle = low + (high - low) / 2;
                BlockIndex left = accelerated.ancestor(a, middle);
                BlockIndex right = accelerated.ancestor(b, middle);
                if (left.hash().equals(right.hash())) {
                    low = middle;
                    common = left;
                } else {
                    high = middle;
                }
            }
            return common;
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
