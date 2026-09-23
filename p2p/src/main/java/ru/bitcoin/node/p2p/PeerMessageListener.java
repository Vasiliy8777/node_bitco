package ru.bitcoin.node.p2p;

import ru.bitcoin.node.p2p.message.BitcoinMessage;

/** Observes unsolicited messages on the reader thread. Implementations must not block. */
@FunctionalInterface
public interface PeerMessageListener {
    void onMessage(Peer peer, BitcoinMessage message);
}
