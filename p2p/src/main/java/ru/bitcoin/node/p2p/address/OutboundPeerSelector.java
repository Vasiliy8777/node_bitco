package ru.bitcoin.node.p2p.address;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class OutboundPeerSelector {

    private final PeerAddressManager addressManager;

    public OutboundPeerSelector(
            PeerAddressManager addressManager
    ) {
        this.addressManager = Objects.requireNonNull(addressManager, "addressManager");
    }

    public Optional<PeerAddress> select(
            Set<PeerAddress> excludedAddresses
    ) {
        Objects.requireNonNull(excludedAddresses, "excludedAddresses");
        return addressManager.select(excludedAddresses);
    }

    public Optional<PeerAddress> select(
            Set<PeerAddress> excludedAddresses,
            Set<PeerNetGroup> excludedNetGroups
    ) {
        Objects.requireNonNull(excludedAddresses, "excludedAddresses");
        Objects.requireNonNull(excludedNetGroups, "excludedNetGroups");

        Set<PeerAddress> rejected = new LinkedHashSet<>(excludedAddresses);

        while (true) {
            PeerAddress candidate = select(rejected).orElse(null);
            if (candidate == null) {
                return Optional.empty();
            }

            if (!PeerNetGroup.isDiversifiable(candidate)
                    || !excludedNetGroups.contains(PeerNetGroup.of(candidate))) {
                return Optional.of(candidate);
            }

            rejected.add(candidate);
        }
    }

    public Optional<PeerAddress> select() {
        return addressManager.select();
    }
}
