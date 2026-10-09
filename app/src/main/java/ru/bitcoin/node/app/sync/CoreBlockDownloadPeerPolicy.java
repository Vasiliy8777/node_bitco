package ru.bitcoin.node.app.sync;

import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.p2p.sync.BlockDownloadPeerPolicy;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.function.*;

/** Core 31.1 ProcessBlockAvailability / FindNextBlocksToDownload eligibility. */
public final class CoreBlockDownloadPeerPolicy implements BlockDownloadPeerPolicy {
    private final BlockIndexLookup lookup;
    private final Supplier<BlockIndex> activeTip;
    private final HeaderChainState headers;
    private final BlockLocatorBuilder locators;
    private final NetworkParameters parameters;
    private final Predicate<BlockIndex> failed;
    private final BooleanSupplier initialDownload;
    private final Supplier<BlockIndex> snapshotBase;
    private final Map<Peer, State> states = new IdentityHashMap<>();
    private final Object refreshLock = new Object();
    private final Map<ForkKey, BlockIndex> forks = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<ForkKey, BlockIndex> eldest) {
            return size() > 2048;
        }
    };
    private Hash256 locatorTip;
    private List<Hash256> cachedLocator;
    // Core uses shared in-memory CBlockIndex ancestry. Cache only immutable hash
    // results here; mutable eligibility/failure decisions are never memoized.
    private final Map<AncestorKey, Hash256> ancestors = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<AncestorKey, Hash256> eldest) {
            return size() > 2048;
        }
    };

    public CoreBlockDownloadPeerPolicy(BlockIndexLookup lookup, Supplier<BlockIndex> activeTip,
            HeaderChainState headers, BlockLocatorBuilder locators, NetworkParameters parameters,
            Predicate<BlockIndex> failed, BooleanSupplier initialDownload, Supplier<BlockIndex> snapshotBase) {
        this.lookup = Objects.requireNonNull(lookup);
        this.activeTip = Objects.requireNonNull(activeTip);
        this.headers = Objects.requireNonNull(headers);
        this.locators = Objects.requireNonNull(locators);
        this.parameters = Objects.requireNonNull(parameters);
        this.failed = Objects.requireNonNull(failed);
        this.initialDownload = Objects.requireNonNull(initialDownload);
        this.snapshotBase = Objects.requireNonNull(snapshotBase);
    }

    @Override
    public void refresh(List<Peer> peers) {
        synchronized (refreshLock) {
            refreshInternal(peers);
        }
    }

    private void refreshInternal(List<Peer> peers) {
        Map<Peer, State> updated = new IdentityHashMap<>();
        synchronized (this) {
            for (Peer peer : peers) {
                if (peer.isReady()) updated.put(peer, new State(states.get(peer)));
            }
        }
        BlockIndex active = activeTip.get();
        BlockIndex headerTip = headers.publishedBestHeaderTip();
        long now = System.nanoTime();
        for (Peer peer : peers) {
            if (!peer.isReady()) continue;
            State state = updated.get(peer);
            if (state == null) continue;
            Hash256 announcement = peer.lastBlockAnnouncement();
            if (announcement != null && !announcement.equals(state.resolvedAnnouncement)) {
                BlockIndex announced = lookup.find(announcement);
                if (announced != null && announced.chainWork().signum() > 0 && !failed.test(announced)) {
                    if (state.bestKnown == null || announced.chainWork().compareTo(state.bestKnown.chainWork()) >= 0)
                        state.bestKnown = announced;
                    state.resolvedAnnouncement = announcement;
                }
            }
            if (state.bestKnown != null && (!active.hash().equals(state.activeAtUpdate)
                    || state.bestAtUpdate != state.bestKnown)) {
                ForkKey key = new ForkKey(state.bestKnown.hash(), active.hash());
                BlockIndex fork = forks.get(key);
                if (fork == null) {
                    fork = contains(state.bestKnown, active) ? active
                            : CommonAncestorFinder.find(state.bestKnown, active, lookup);
                    forks.put(key, fork);
                }
                if (state.lastCommon == null || fork.chainWork().compareTo(state.lastCommon.chainWork()) > 0
                        || !contains(state.bestKnown, state.lastCommon)) state.lastCommon = fork;
                state.activeAtUpdate = active.hash();
                state.bestAtUpdate = state.bestKnown;
            }
            // VERSION.startHeight is not proof of block availability. A locator from
            // the best header's parent asks a same-chain peer to announce that tip.
            if ((state.bestKnown == null || state.bestKnown.height() < headerTip.height())
                    && now >= state.nextHeadersRequest) {
                state.nextHeadersRequest = now + Duration.ofMinutes(2).toNanos();
                try {
                    peer.sendAsync(BitcoinMessages.getHeaders(new GetHeadersMessage(
                            Math.min(VersionMessage.CURRENT_PROTOCOL_VERSION, peer.remoteVersion().version()),
                            availabilityLocator(headerTip), new Hash256(new byte[32]))));
                } catch (IOException | IllegalStateException ignored) {
                    // Transport owns disconnection and retry; this does not make the peer eligible.
                }
            }
        }
        // Publish a complete snapshot. Persistent ancestry reads above must not
        // hold the monitor used by download eligibility decisions.
        synchronized (this) {
            states.clear();
            states.putAll(updated);
        }
    }

    @Override
    public synchronized boolean canServe(Peer peer, Hash256 hash, Long height) {
        if (!canTraverse(peer, hash, height)) return false;
        return (peer.remoteVersion().services() & VersionMessage.NODE_NETWORK) != 0
                || states.get(peer).bestKnown.height() - lookup.find(hash).height() < 286;
    }

    @Override
    public synchronized boolean canTraverse(Peer peer, Hash256 hash, Long height) {
        State state = states.get(peer);
        if (!peer.isReady() || state == null || state.bestKnown == null || state.lastCommon == null) return false;
        BlockIndex best = state.bestKnown;
        if (best.chainWork().compareTo(activeTip.get().chainWork()) < 0
                || best.chainWork().compareTo(parameters.minimumChainWork()) < 0 || failed.test(best)) return false;
        BlockIndex requested = lookup.find(hash);
        if (requested == null || (height != null && requested.height() != height)
                || failed.test(requested) || !contains(best, requested)) return false;
        VersionMessage version = peer.remoteVersion();
        if (requested.height() >= parameters.segwitHeight()
                && (version.services() & VersionMessage.NODE_WITNESS) == 0) return false;
        if ((version.services() & VersionMessage.NODE_NETWORK) == 0) {
            if ((version.services() & VersionMessage.NODE_NETWORK_LIMITED) == 0
                    || initialDownload.getAsBoolean()) return false;
        }
        BlockIndex base = snapshotBase.get();
        return base == null || contains(best, base);
    }

    @Override
    public synchronized boolean canDownload(Peer peer, Hash256 hash, Long height) {
        if (!canServe(peer, hash, height)) return false;
        return lookup.find(hash).height() <= states.get(peer).lastCommon.height() + 1024;
    }

    @Override
    public synchronized boolean canProbeWindowEnd(Peer peer, Hash256 hash, Long height) {
        return canTraverse(peer, hash, height)
                && lookup.find(hash).height() == states.get(peer).lastCommon.height() + 1025;
    }

    private boolean contains(BlockIndex tip, BlockIndex block) {
        if (block.height() > tip.height()) return false;
        AncestorKey key = new AncestorKey(tip.hash(), block.height());
        Hash256 cached;
        synchronized (ancestors) { cached = ancestors.get(key); }
        if (cached != null) return cached.equals(block.hash());
        BlockIndex ancestor;
        if (lookup instanceof BlockIndexAncestorLookup accelerated)
            ancestor = accelerated.ancestor(tip, block.height());
        else {
            ancestor = tip;
            while (ancestor.height() > block.height())
                ancestor = Objects.requireNonNull(lookup.find(ancestor.previousBlockHash()));
        }
        synchronized (ancestors) { ancestors.put(key, ancestor.hash()); }
        return ancestor.hash().equals(block.hash());
    }

    private record AncestorKey(Hash256 tip, long height) { }
    private record ForkKey(Hash256 best, Hash256 active) { }

    // All peers receive the same immutable locator for this header tip. Rebuild
    // only when its hash changes, including a same-height header reorganization.
    private List<Hash256> availabilityLocator(BlockIndex headerTip) {
        if (!headerTip.hash().equals(locatorTip)) {
            BlockIndex anchor = headerTip.height() == 0 ? headerTip
                    : Objects.requireNonNull(lookup.find(headerTip.previousBlockHash()));
            List<Hash256> built = List.copyOf(locators.build(anchor));
            cachedLocator = built;
            locatorTip = headerTip.hash();
        }
        return cachedLocator;
    }

    private static final class State {
        BlockIndex bestKnown, lastCommon, bestAtUpdate;
        Hash256 resolvedAnnouncement, activeAtUpdate;
        long nextHeadersRequest;

        State(State previous) {
            if (previous == null) return;
            bestKnown = previous.bestKnown;
            lastCommon = previous.lastCommon;
            bestAtUpdate = previous.bestAtUpdate;
            resolvedAnnouncement = previous.resolvedAnnouncement;
            activeAtUpdate = previous.activeAtUpdate;
            nextHeadersRequest = previous.nextHeadersRequest;
        }
    }
}
