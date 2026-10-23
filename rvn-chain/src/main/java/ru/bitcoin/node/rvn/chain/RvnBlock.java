package ru.bitcoin.node.rvn.chain;

import java.util.*;
import ru.bitcoin.node.rvn.protocol.RvnBlockHeader;
import ru.bitcoin.node.protocol.transaction.Transaction;

/** Parsed block data; construction is not a consensus-validation claim. */
public record RvnBlock(RvnBlockHeader header, List<Transaction> transactions) {
    public RvnBlock { Objects.requireNonNull(header); transactions = List.copyOf(transactions); }
}
