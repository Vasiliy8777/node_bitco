package ru.bitcoin.node.p2p.address;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class OutboundPeerSelector {

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

    public Optional<PeerAddress> select(
            Set<PeerAddress> excludedAddresses
    ) {

        Objects.requireNonNull(
                excludedAddresses,
                "excludedAddresses"
        );

        return addressManager.select(
                excludedAddresses
        );
    }

    public Optional<PeerAddress> select() {

        return addressManager.select();
    }
}