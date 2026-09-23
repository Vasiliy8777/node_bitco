package ru.bitcoin.node.p2p.address;

import ru.bitcoin.node.p2p.PeerProtocolException;

public final class PeerAddressProtocolException
        extends PeerProtocolException {

    public PeerAddressProtocolException(
            String message
    ) {
        super(message);
    }

    public PeerAddressProtocolException(
            String message,
            Throwable cause
    ) {
        super(
                message,
                cause
        );
    }
}