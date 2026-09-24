package ru.bitcoin.node.p2p;

import ru.bitcoin.node.p2p.address.PeerAddress;

import java.util.Objects;

public record OutboundPeerConnection(
        Peer peer,
        PeerAddress address,
        PeerConnectionRole role
) {
    public OutboundPeerConnection(Peer peer, PeerAddress address) {
        this(peer, address, PeerConnectionRole.FULL_RELAY);
    }

    public OutboundPeerConnection {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(role, "role");
        if (!role.persistentOutbound()) {
            throw new IllegalArgumentException("OutboundPeerConnection requires a persistent outbound role");
        }
    }
}
