package ru.bitcoin.node.p2p.message;

public record SendCmpctMessage(boolean highBandwidth, long version) {
    public SendCmpctMessage {
        if (version != 1 && version != 2)
            throw new IllegalArgumentException("BIP152 compact-block version must be 1 or 2");
    }
}
