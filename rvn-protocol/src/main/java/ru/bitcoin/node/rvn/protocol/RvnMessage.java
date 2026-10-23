package ru.bitcoin.node.rvn.protocol;

public record RvnMessage(String command, byte[] payload) {
 public RvnMessage {
        if (command == null || !command.matches("[a-z0-9_]{1,12}"))
            throw new IllegalArgumentException("Invalid P2P command");
        if (payload == null) throw new IllegalArgumentException("null payload");
        payload = payload.clone();
    }
    @Override public byte[] payload () {
        return payload.clone();
    }
    public int payloadSize() { return payload.length; }
}
