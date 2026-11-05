package ru.bitcoin.node.app.service;

import org.springframework.context.SmartLifecycle;
import ru.bitcoin.node.p2p.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntSupplier;

/** Explicit manual peers use bounded reconnect tasks outside automatic slots. */
public final class ManualPeerConnections implements SmartLifecycle, AutoCloseable {
    public record Endpoint(String host, int port) {
        public Endpoint {
            if (host == null || host.isBlank() || host.chars().anyMatch(Character::isWhitespace)
                    || port < 1 || port > 65535) throw new IllegalArgumentException("Invalid manual peer endpoint");
            host = host.toLowerCase(Locale.ROOT);
            if (host.endsWith(".onion") || host.endsWith(".i2p"))
                throw new IllegalArgumentException("Manual privacy-network peers require their corresponding transport");
        }
    }
    private final PeerConnector connector;
    private final PeerManager peers;
    private final IntSupplier height;
    private final List<Endpoint> endpoints;
    private final Map<Endpoint, Peer> connections = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor executor;
    private volatile boolean running, closed;

    public ManualPeerConnections(PeerConnector connector, PeerManager peers, IntSupplier height, List<Endpoint> endpoints) {
        this.connector = Objects.requireNonNull(connector); this.peers = Objects.requireNonNull(peers);
        this.height = Objects.requireNonNull(height); this.endpoints = List.copyOf(new LinkedHashSet<>(endpoints));
        if (this.endpoints.size() > 8) throw new IllegalArgumentException("At most eight manual peers are supported");
        executor = new ScheduledThreadPoolExecutor(Math.max(1, this.endpoints.size()),
                Thread.ofPlatform().daemon().name("bitcoin-manual-peer-", 0).factory());
        executor.setRemoveOnCancelPolicy(true);
    }
    public static List<Endpoint> parse(String specification, int defaultPort) {
        if (specification.isBlank()) return List.of();
        var result = new LinkedHashSet<Endpoint>();
        for (String raw : specification.split(",", -1)) {
            String host = raw.trim(); int port = defaultPort;
            if (host.startsWith("[")) {
                int end = host.indexOf(']');
                if (end < 0) throw new IllegalArgumentException("Unclosed IPv6 manual peer address");
                String suffix = host.substring(end + 1);
                if (!suffix.isEmpty()) {
                    if (!suffix.startsWith(":")) throw new IllegalArgumentException("Invalid manual peer port");
                    port = Integer.parseInt(suffix.substring(1));
                }
                host = host.substring(1, end);
            } else if (host.indexOf(':') == host.lastIndexOf(':') && host.contains(":")) {
                int separator = host.indexOf(':'); port = Integer.parseInt(host.substring(separator + 1)); host = host.substring(0, separator);
            }
            result.add(new Endpoint(host, port));
        }
        if (result.size() > 8) throw new IllegalArgumentException("At most eight manual peers are supported");
        return List.copyOf(result);
    }
    @Override public synchronized void start() {
        if (closed) throw new IllegalStateException("Manual peer connections already closed");
        if (running) return;
        running = true;
        for (var endpoint : endpoints) executor.scheduleWithFixedDelay(() -> connect(endpoint), 0, 5, TimeUnit.SECONDS);
    }
    private void connect(Endpoint endpoint) {
        if (!running) return;
        var current = connections.get(endpoint);
        if (current != null && current.isReady()) return;
        Peer connected = null;
        try {
            connected = connector.connectManaged(endpoint.host(), endpoint.port(), height.getAsInt(), PeerConnectionRole.MANUAL);
            if (!running) { connected.close(); return; }
            connections.put(endpoint, connected);
            connected.addCloseListener((peer, cause) -> connections.remove(endpoint, peer));
            // Stop can race between the first running check and registration.
            // Once published in the map, close() owns the peer; if it already
            // ran, this worker must close the late connection itself.
            if (!running) { connections.remove(endpoint, connected); connected.close(); return; }
            peers.add(connected, PeerConnectionRole.MANUAL);
        } catch (IOException | RuntimeException failure) {
            if (connected != null) try { connected.close(); } catch (IOException ignored) { }
            System.getLogger(getClass().getName()).log(System.Logger.Level.DEBUG, "Manual peer connection failed: {0}:{1}", endpoint.host(), endpoint.port());
        }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; running = false; executor.shutdownNow(); connector.cancelPendingConnections();
        for (var peer : connections.values()) try { peer.close(); } catch (IOException ignored) { }
        connections.clear();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    @Override public void stop() { close(); }
    @Override public void stop(Runnable callback) { try { close(); } finally { callback.run(); } }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return 1; }
}
