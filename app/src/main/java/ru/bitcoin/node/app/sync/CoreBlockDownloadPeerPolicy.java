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
    public synchronized void refresh(List<Peer> peers) {
        states.keySet().removeIf(peer -> !peers.contains(peer) || !peer.isReady());
        BlockIndex active = activeTip.get();
        BlockIndex headerTip = headers.bestHeaderTip();
        long now = System.nanoTime();
        for (Peer peer : peers) {
            if (!peer.isReady()) continue;
            State state = states.computeIfAbsent(peer, ignored -> new State());
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
                BlockIndex fork = CommonAncestorFinder.find(state.bestKnown, active, lookup);
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
                BlockIndex anchor = headerTip.height() == 0 ? headerTip : lookup.find(headerTip.previousBlockHash());
                try {
                    peer.sendAsync(BitcoinMessages.getHeaders(new GetHeadersMessage(
                            Math.min(VersionMessage.CURRENT_PROTOCOL_VERSION, peer.remoteVersion().version()),
                            locators.build(anchor), new Hash256(new byte[32]))));
                } catch (IOException | IllegalStateException ignored) {
                    // Transport owns disconnection and retry; this does not make the peer eligible.
                }
            }
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
        if (lookup instanceof BlockIndexAncestorLookup accelerated)
            return accelerated.ancestor(tip, block.height()).hash().equals(block.hash());
        while (tip.height() > block.height()) tip = Objects.requireNonNull(lookup.find(tip.previousBlockHash()));
        return tip.hash().equals(block.hash());
    }

    private static final class State {
        BlockIndex bestKnown, lastCommon, bestAtUpdate;
        Hash256 resolvedAnnouncement, activeAtUpdate;
        long nextHeadersRequest;
    }
}
