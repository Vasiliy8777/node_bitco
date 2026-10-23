package ru.bitcoin.node.rvn.p2p;

import ru.bitcoin.node.rvn.protocol.*;

import java.io.IOException;
import java.util.*;

/**
 * One explicit, bounded fetch. Locators MUST be supplied by the caller from trusted sources.
 * No automatic pagination: next locators require verified Ravencoin block IDs.
 */
public final class RvnHeadersProbeCoordinator {
    private final RvnPeerSelector selector;
    private final RvnHeadersIngestor ingestor;
    private final RvnNetwork network;
    private final int connectTimeoutMs, queueCapacity;

    public RvnHeadersProbeCoordinator(RvnPeerSelector selector, RvnHeadersIngestor ingestor,
                                      RvnNetwork network, int connectTimeoutMs, int queueCapacity) {
        this.selector = Objects.requireNonNull(selector);
        this.ingestor = Objects.requireNonNull(ingestor);
        this.network = Objects.requireNonNull(network);
        if (connectTimeoutMs < 100 || connectTimeoutMs > 60000 || queueCapacity < 1 || queueCapacity > 4096)
            throw new IllegalArgumentException("connection bounds");
        this.connectTimeoutMs = connectTimeoutMs;
        this.queueCapacity = queueCapacity;
    }

    public Optional<RvnHeadersIngestor.Receipt> fetchOnce(int protocolVersion, List<byte[]> trustedLocators,
                                                          byte[] stopHash, long activationTime, long responseTimeoutMs) throws IOException, InterruptedException {
        // Validate request BEFORE opening a network connection or consuming a peer slot.
        RvnGetHeaders.encode(protocolVersion, trustedLocators, stopHash);
        long now = System.nanoTime();
        Optional<RvnPeerEndpoint> candidate = selector.next(now);
        if (candidate.isEmpty()) return Optional.empty();
        RvnPeerEndpoint endpoint = candidate.get();
        try (RvnPeerSession session = RvnPeerSession.connect(network, endpoint.host(), endpoint.port(),
                connectTimeoutMs, queueCapacity)) {
            List<RvnBlockHeader> headers = RvnHeadersClient.request(session, protocolVersion,
                    trustedLocators, stopHash, activationTime, responseTimeoutMs);
            RvnHeadersIngestor.Receipt receipt = ingestor.accept(headers);
            selector.success(endpoint);
            return Optional.of(receipt);
        } catch (IOException | RuntimeException e) {
            selector.failure(endpoint, System.nanoTime());
            throw e;
        }
    }
}
