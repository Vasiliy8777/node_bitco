package ru.bitcoin.node.p2p.message;

import ru.bitcoin.node.common.types.Hash256;

import java.util.List;

public final class GetHeadersMessage {

    public static final int MAX_LOCATOR_HASHES = 101;

    private final int protocolVersion;
    private final List<Hash256> locatorHashes;
    private final Hash256 stopHash;

    public GetHeadersMessage(
            int protocolVersion,
            List<Hash256> locatorHashes,
            Hash256 stopHash
    ) {
        if (locatorHashes == null) {
            throw new IllegalArgumentException(
                    "locatorHashes must not be null"
            );
        }

        if (locatorHashes.isEmpty()) {
            throw new IllegalArgumentException(
                    "locatorHashes must not be empty"
            );
        }

        if (locatorHashes.size() > MAX_LOCATOR_HASHES) {
            throw new IllegalArgumentException(
                    "Too many locator hashes: "
                            + locatorHashes.size()
            );
        }

        if (locatorHashes.stream().anyMatch(
                hash -> hash == null
        )) {
            throw new IllegalArgumentException(
                    "locatorHashes must not contain null"
            );
        }

        if (stopHash == null) {
            throw new IllegalArgumentException(
                    "stopHash must not be null"
            );
        }

        this.protocolVersion = protocolVersion;
        this.locatorHashes = List.copyOf(locatorHashes);
        this.stopHash = stopHash;
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public List<Hash256> locatorHashes() {
        return locatorHashes;
    }

    public Hash256 stopHash() {
        return stopHash;
    }
}