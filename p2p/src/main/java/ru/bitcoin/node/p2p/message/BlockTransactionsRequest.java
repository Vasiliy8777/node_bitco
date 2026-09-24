package ru.bitcoin.node.p2p.message;

import ru.bitcoin.node.common.types.Hash256;

import java.util.*;

public record BlockTransactionsRequest(Hash256 blockHash, List<Integer> indexes) {
    public BlockTransactionsRequest {
        Objects.requireNonNull(blockHash, "blockHash");
        Objects.requireNonNull(indexes, "indexes");
        indexes = List.copyOf(indexes);
        int p = -1;
        for (Integer i : indexes) {
            if (i == null || i < 0 || i <= p)
                throw new IllegalArgumentException("indexes must be strictly increasing non-negative values");
            p = i;
        }
    }
}
