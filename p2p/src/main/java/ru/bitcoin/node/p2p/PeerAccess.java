package ru.bitcoin.node.p2p;

import java.util.*;

/** Immutable connection access selected before the handshake by local configuration. */
public record PeerAccess(Set<PeerPermission> permissions, boolean manual) {
    public PeerAccess {
        var copy = EnumSet.noneOf(PeerPermission.class);
        copy.addAll(Objects.requireNonNull(permissions));
        if (copy.contains(PeerPermission.NOBAN)) copy.add(PeerPermission.DOWNLOAD);
        if (copy.contains(PeerPermission.FORCERELAY)) copy.add(PeerPermission.RELAY);
        permissions = Set.copyOf(copy);
    }
    public static PeerAccess ordinary() { return new PeerAccess(Set.of(), false); }
    public static PeerAccess manualConnection() { return new PeerAccess(Set.of(), true); }
}
