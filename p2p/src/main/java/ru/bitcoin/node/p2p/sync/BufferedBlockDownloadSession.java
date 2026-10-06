package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Keeps network queues moving while the consumer validates an ordered batch. */
public final class BufferedBlockDownloadSession implements BlockDownloadSession {
    private final BlockDownloadSession delegate;
    private final BlockingQueue<Outcome> completions = new LinkedBlockingQueue<>();
    private final Thread worker;
    private int pending;
    private boolean closed;
    private IOException failure;

    public BufferedBlockDownloadSession(BlockDownloadSession delegate) {
        this.delegate = Objects.requireNonNull(delegate);
        worker = Thread.ofVirtual().name("ibd-download-completions").start(this::receive);
    }

    @Override
    public synchronized void submit(List<Hash256> hashes) throws IOException {
        ensureOpen();
        int updated = Math.addExact(pending, hashes.size());
        delegate.submit(hashes);
        pending = updated;
        notifyAll();
    }

    @Override
    public synchronized void submitRequests(List<BlockDownloadRequest> requests) throws IOException {
        ensureOpen();
        int updated = Math.addExact(pending, requests.size());
        delegate.submitRequests(requests);
        pending = updated;
        notifyAll();
    }

    private void receive() {
        try {
            while (true) {
                synchronized (this) {
                    while (!closed && delegate.pendingCount() == 0) wait();
                    if (closed) return;
                }
                Optional<CompletedBlockDownload> completed = delegate.pollCompleted(Duration.ofMillis(250));
                if (completed.isPresent()) {
                    synchronized (this) {
                        if (closed) return;
                        completions.add(new Outcome(completed.get(), null));
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(new IOException("Block download receiver interrupted", exception));
        } catch (IOException exception) {
            fail(exception);
        } catch (RuntimeException exception) {
            fail(new IOException("Block download receiver failed", exception));
        }
    }

    private synchronized void fail(IOException exception) {
        if (!closed) {
            failure = exception;
            completions.add(new Outcome(null, exception));
            notifyAll();
        }
    }

    @Override
    public CompletedBlockDownload awaitCompleted() throws IOException {
        while (true) {
            var completed = pollCompleted(Duration.ofMillis(250));
            if (completed.isPresent()) return completed.get();
        }
    }

    @Override
    public Optional<CompletedBlockDownload> pollCompleted(Duration timeout) throws IOException {
        Objects.requireNonNull(timeout);
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        synchronized (this) {
            ensureOpen();
            if (pending == 0) throw new IllegalStateException("No pending block downloads");
        }
        final Outcome outcome;
        try {
            outcome = completions.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Block download interrupted", exception);
        }
        synchronized (this) {
            ensureOpen();
            if (outcome == null) return Optional.empty();
            if (outcome.failure() != null) throw outcome.failure();
            pending = Math.decrementExact(pending);
            return Optional.of(outcome.completed());
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Block download session closed");
        if (failure != null) throw failure;
    }

    @Override public synchronized int pendingCount() { return pending; }
    @Override public Optional<Peer> inFlightPeer(Hash256 hash) { return delegate.inFlightPeer(hash); }
    @Override public boolean hasIdlePeerFor(BlockDownloadRequest request, Peer blocker) {
        return delegate.hasIdlePeerFor(request, blocker);
    }
    @Override public void failPeer(Peer peer, IOException cause) throws IOException { delegate.failPeer(peer, cause); }
    @Override public Optional<Peer> findWindowStaller(BlockDownloadRequest request,
            java.util.function.BiFunction<Peer, BlockDownloadPeerPolicy, Optional<Peer>> probe) {
        return delegate.findWindowStaller(request, probe);
    }
    @Override public boolean retryBlock(Hash256 hash, Peer peer, IOException cause) throws IOException {
        return delegate.retryBlock(hash, peer, cause);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            completions.clear();
            completions.add(new Outcome(null, new IOException("Block download session closed")));
            notifyAll();
        }
        worker.interrupt();
        // Interrupt an ancestry/assignment walk before waiting for the delegate
        // monitor it owns. Otherwise cancellation cannot reach that worker.
        delegate.close();
        if (Thread.currentThread() != worker) {
            try { worker.join(5_000); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        }
    }

    private record Outcome(CompletedBlockDownload completed, IOException failure) { }
}
