package ru.bitcoin.node.p2p.address;

import java.util.List;
import java.util.Objects;

public final class OutboundPeerSelector {

    private final PeerAddressManager addressManager;

    public OutboundPeerSelector(
            PeerAddressManager addressManager
    ) {

        this.addressManager =
                Objects.requireNonNull(
                        addressManager,
                        "addressManager"
                );
    }

    public List<PeerAddress> candidates() {

        return addressManager.candidates();
    }
}