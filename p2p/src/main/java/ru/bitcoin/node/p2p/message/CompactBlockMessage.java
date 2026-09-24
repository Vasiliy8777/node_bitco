package ru.bitcoin.node.p2p.message;

import ru.bitcoin.node.protocol.block.BlockHeader;

import java.util.*;

public record CompactBlockMessage(BlockHeader header, long nonce, List<Long> shortIds,
                                  List<PrefilledTransaction> prefilledTransactions) {
    public static final long SHORT_ID_MASK = 0x0000ffffffffffffL;

    public CompactBlockMessage {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(shortIds, "shortIds");
        Objects.requireNonNull(prefilledTransactions, "prefilledTransactions");
        shortIds = List.copyOf(shortIds);
        prefilledTransactions = List.copyOf(prefilledTransactions);
        for (Long id : shortIds)
            if (id == null || (id & ~SHORT_ID_MASK) != 0)
                throw new IllegalArgumentException("short id must fit 48 bits");
        int previous = -1;
        for (PrefilledTransaction p : prefilledTransactions) {
            if (p.index() <= previous)
                throw new IllegalArgumentException("prefilled indexes must be strictly increasing");
            previous = p.index();
        }
    }

    public int transactionCount() {
        return Math.addExact(shortIds.size(), prefilledTransactions.size());
    }
}
