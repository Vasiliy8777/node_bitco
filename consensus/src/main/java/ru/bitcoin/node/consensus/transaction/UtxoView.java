package ru.bitcoin.node.consensus.transaction;

import ru.bitcoin.node.protocol.transaction.OutPoint;

import java.util.Optional;

@FunctionalInterface
public interface UtxoView {

    Optional<UtxoEntry> find(
            OutPoint outPoint
    );
}