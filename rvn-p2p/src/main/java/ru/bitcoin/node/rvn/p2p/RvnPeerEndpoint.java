package ru.bitcoin.node.rvn.p2p;

import java.util.Objects;

public record RvnPeerEndpoint(String host, int port) {
    public RvnPeerEndpoint {
        Objects.requireNonNull(host, "host");
        if (host.isBlank() || host.length() > 253 || port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid peer endpoint");
    }
}
