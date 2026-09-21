package ru.bitcoin.node.p2p;

import ru.bitcoin.node.p2p.address.PeerAddress;

import java.util.Objects;

public record OutboundPeerConnection(
        Peer peer,
        PeerAddress address
) {

    public OutboundPeerConnection {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        Objects.requireNonNull(
                address,
                "address"
        );
    }
}