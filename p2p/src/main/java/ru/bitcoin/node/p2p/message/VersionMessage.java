package ru.bitcoin.node.p2p.message;

public final class VersionMessage {

    public static final int CURRENT_PROTOCOL_VERSION = 70017;
    public static final int MIN_PEER_PROTOCOL_VERSION = 31800;

    public static final long NODE_NETWORK = 1L << 0;
    public static final long NODE_WITNESS = 1L << 3;
    public static final long NODE_NETWORK_LIMITED = 1L << 10;

    public static final long DEFAULT_SERVICES =
            NODE_NETWORK | NODE_WITNESS;

    private final int version;
    private final long services;
    private final long timestamp;
    private final NetworkAddress receiverAddress;
    private final NetworkAddress senderAddress;
    private final long nonce;
    private final String userAgent;
    private final int startHeight;
    private final boolean relay;

    public VersionMessage(
            int version,
            long services,
            long timestamp,
            NetworkAddress receiverAddress,
            NetworkAddress senderAddress,
            long nonce,
            String userAgent,
            int startHeight,
            boolean relay
    ) {
        if (version <= 0) {
            throw new IllegalArgumentException(
                    "version must be positive"
            );
        }

        if (receiverAddress == null) {
            throw new IllegalArgumentException(
                    "receiverAddress must not be null"
            );
        }

        if (senderAddress == null) {
            throw new IllegalArgumentException(
                    "senderAddress must not be null"
            );
        }

        if (userAgent == null) {
            throw new IllegalArgumentException(
                    "userAgent must not be null"
            );
        }

        byte[] userAgentBytes =
                userAgent.getBytes(
                        java.nio.charset.StandardCharsets.UTF_8
                );

        if (userAgentBytes.length > 256) {
            throw new IllegalArgumentException(
                    "userAgent exceeds 256 bytes"
            );
        }

        this.version = version;
        this.services = services;
        this.timestamp = timestamp;
        this.receiverAddress = receiverAddress;
        this.senderAddress = senderAddress;
        this.nonce = nonce;
        this.userAgent = userAgent;
        this.startHeight = startHeight;
        this.relay = relay;
    }

    public int version() {
        return version;
    }

    public long services() {
        return services;
    }

    public long timestamp() {
        return timestamp;
    }

    public NetworkAddress receiverAddress() {
        return receiverAddress;
    }

    public NetworkAddress senderAddress() {
        return senderAddress;
    }

    public long nonce() {
        return nonce;
    }

    public String userAgent() {
        return userAgent;
    }

    public int startHeight() {
        return startHeight;
    }

    public boolean relay() {
        return relay;
    }
}