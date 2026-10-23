package ru.bitcoin.node.rvn.chain;

import ru.bitcoin.node.protocol.transaction.OutPoint;
import java.util.Optional;

/** A stable snapshot of unspent coins at the candidate's parent. */
@FunctionalInterface
public interface RvnCoinView {
    Optional<RvnCoin> find(OutPoint outPoint);
}
