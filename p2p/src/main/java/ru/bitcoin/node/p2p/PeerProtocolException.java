package ru.bitcoin.node.p2p;

/**
 * Signals that a peer sent a message which cannot be
 * processed according to the Bitcoin P2P protocol.
 *
 * Unlike an ordinary observer RuntimeException, this exception
 * is promoted by Peer to an IOException so that the reader
 * terminates the connection instead of silently swallowing
 * the protocol failure.
 */
public class PeerProtocolException extends RuntimeException {

    public PeerProtocolException(String message) {
        super(message);
    }

    public PeerProtocolException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}