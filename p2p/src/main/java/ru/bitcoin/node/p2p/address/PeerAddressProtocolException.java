package ru.bitcoin.node.p2p.address;

public final class PeerAddressProtocolException
        extends RuntimeException {

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