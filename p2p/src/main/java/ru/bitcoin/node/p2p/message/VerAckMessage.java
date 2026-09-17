package ru.bitcoin.node.p2p.message;

public final class VerAckMessage {

    public static final VerAckMessage INSTANCE =
            new VerAckMessage();

    private VerAckMessage() {
    }

    public byte[] payload() {
        return new byte[0];
    }
}