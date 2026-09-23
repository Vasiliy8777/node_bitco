package ru.bitcoin.node.app.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.message.InventoryVector;
import ru.bitcoin.node.p2p.sync.HeaderSynchronizer;

import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;

/** Runs on the lifecycle worker, never on a peer's message reader. */
public final class LiveChainSynchronizer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LiveChainSynchronizer.class);
    private final PeerManager peers;
    private final NodeSyncInfrastructure infrastructure;
    private final BlockSyncCoordinator blocks;
    private final NodeValidationService validation;
    private final Duration timeout;
    private final Semaphore wakeup = new Semaphore(0);
    private final Set<Peer> observed = new HashSet<>();
    private volatile boolean closed;
    private volatile long lastSuccess;
    private final PeerInventoryListener inventoryListener = (peer, inventory) -> {
        if (inventory.inventory().stream().anyMatch(v -> v.type() == InventoryVector.MSG_BLOCK
                || v.type() == InventoryVector.MSG_WITNESS_BLOCK)) requestSync();
    };
    private final PeerMessageListener messageListener = (peer, message) -> {
        if (message.command().equals("headers")) requestSync();
    };

    public LiveChainSynchronizer(PeerManager peers, NodeSyncInfrastructure infrastructure,
                                 BlockSyncCoordinator blocks, NodeValidationService validation, Duration timeout) {
        this.peers = peers;
        this.infrastructure = infrastructure;
        this.blocks = blocks;
        this.validation = validation;
        this.timeout = timeout;
    }

    public void requestSync() {
        synchronized (wakeup) {
            lastSuccess = 0;
            if (wakeup.availablePermits() == 0) wakeup.release();
        }
        synchronized (this) { notifyAll(); }
    }

    public boolean isCurrent() {
        long success = lastSuccess;
        return !closed && success != 0 && System.nanoTime() - success < Duration.ofSeconds(60).toNanos()
                && !peers.readyPeers().isEmpty()
                && validation.activeTip().hash().equals(infrastructure.headerChainState().bestHeaderTip().hash());
    }

    public void run() throws IOException {
        long nextPoll = 0;
        try {
            while (!closed) {
                var ready = peers.readyPeers();
                observed.removeIf(peer -> {
                    if (ready.contains(peer)) return false;
                    detach(peer);
                    return true;
                });
                for (Peer peer : ready) {
                    if (observed.add(peer)) {
                        peer.addInventoryListener(inventoryListener);
                        peer.addMessageListener(messageListener);
                        requestSync(); // Also covers announcements lost during handshake/reconnect.
                    }
                }
                boolean signalled = wakeup.tryAcquire();
                if (signalled || System.nanoTime() >= nextPoll) {
                    nextPoll = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                    for (Peer peer : ready) {
                        if (closed) break;
                        try {
                            new HeaderSyncCoordinator(new HeaderSynchronizer(peer, timeout),
                                    infrastructure.headerSyncService(), infrastructure.headerChainState(),
                                    infrastructure.blockLocatorBuilder()).synchronize(new Hash256(new byte[32]));
                            if (closed) break;
                            blocks.synchronize();
                            synchronized (wakeup) {
                                if (wakeup.availablePermits() == 0) lastSuccess = System.nanoTime();
                            }
                        } catch (IOException | IllegalArgumentException
                                 | ru.bitcoin.node.consensus.block.BlockHeaderValidationException
                                 | ru.bitcoin.node.consensus.block.BlockValidationException
                                 | ru.bitcoin.node.consensus.transaction.TransactionValidationException exception) {
                            lastSuccess = 0;
                            log.warn("Live chain synchronization failed; replacing peer", exception);
                            try { peer.close(); } catch (IOException closeFailure) { exception.addSuppressed(closeFailure); }
                        }
                    }
                }
                synchronized (this) {
                    if (!closed && wakeup.availablePermits() == 0) wait(1_000);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (!closed) throw new IOException("Live chain synchronization interrupted", exception);
        } finally {
            observed.forEach(this::detach);
            observed.clear();
            lastSuccess = 0;
        }
    }

    private void detach(Peer peer) {
        peer.removeInventoryListener(inventoryListener);
        peer.removeMessageListener(messageListener);
    }

    @Override public synchronized void close() {
        closed = true;
        lastSuccess = 0;
        notifyAll();
    }
}
