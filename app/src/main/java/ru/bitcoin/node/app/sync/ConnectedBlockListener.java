package ru.bitcoin.node.app.sync;

import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.protocol.block.Block;

/** Receives a block only after it became the active chain tip. */
@FunctionalInterface
public interface ConnectedBlockListener {
    ConnectedBlockListener NOOP = (block, sourcePeer) -> { };

    void onConnected(Block block, Peer sourcePeer);
}
