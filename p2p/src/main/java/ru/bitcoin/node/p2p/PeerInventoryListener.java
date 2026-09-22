package ru.bitcoin.node.p2p;

import ru.bitcoin.node.p2p.message.InvMessage;

@FunctionalInterface
public interface PeerInventoryListener {

    void onInventory(
            Peer peer,
            InvMessage inventory
    );
}