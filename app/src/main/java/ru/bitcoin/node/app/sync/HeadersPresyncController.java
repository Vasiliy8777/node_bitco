package ru.bitcoin.node.app.sync;

import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.consensus.block.BlockHeaderValidationException;
import ru.bitcoin.node.consensus.pow.ChainWork;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.LongSupplier;

/** Per-connection Core header anti-DoS state, shared by sync waiters and async relay. */
public final class HeadersPresyncController {
    public record Decision(List<BlockHeader> ready, List<Hash256> nextLocator, boolean handled) {
        public Decision { ready = List.copyOf(ready); nextLocator = List.copyOf(nextLocator); }
    }
    private final NetworkParameters network;
    private final BlockIndexLookup lookup;
    private final HeaderChainState headers;
    private final BlockLocatorBuilder locators;
    private final Supplier<BlockIndex> active;
    private final LongSupplier now;
    private final Map<Object, HeadersPresyncState> states = new WeakHashMap<>();

    public HeadersPresyncController(NetworkParameters network, BlockIndexLookup lookup, HeaderChainState headers,
            BlockLocatorBuilder locators, Supplier<BlockIndex> active, LongSupplier now) {
        this.network = Objects.requireNonNull(network); this.lookup = Objects.requireNonNull(lookup);
        this.headers = Objects.requireNonNull(headers); this.locators = Objects.requireNonNull(locators);
        this.active = Objects.requireNonNull(active); this.now = Objects.requireNonNull(now);
    }
    public BigInteger threshold() {
        var tip = active.get();
        return network.minimumChainWork().max(tip.chainWork().subtract(
                ChainWork.blockWork(tip.header().bits().value()).multiply(BigInteger.valueOf(144))).max(BigInteger.ZERO));
    }
    public synchronized void forget(Object peer) {
        var state = states.remove(peer); if (state != null) state.finish();
    }
    public synchronized long presyncedHeight(Object peer) {
        var state = states.get(peer);
        return state == null ? -1 : state.presyncedHeight();
    }
    public boolean compactHasSufficientWork(BlockHeader header) {
        var parent = lookup.find(header.previousBlockHash());
        if (parent == null) return false;
        BigInteger added;
        try { added = ChainWork.blockWork(header.bits().value()); }
        catch (IllegalArgumentException invalidTarget) { added = BigInteger.ZERO; }
        return parent.chainWork().add(added).compareTo(threshold()) >= 0;
    }
    public synchronized Decision receive(Object peer, List<BlockHeader> received, Runnable acknowledged) {
        Objects.requireNonNull(peer, "peer"); Objects.requireNonNull(received, "received");
        Objects.requireNonNull(acknowledged, "acknowledged");
        if (received.size() > 2000) throw new IllegalArgumentException("Too many headers in a peer response");
        if (received.isEmpty()) { forget(peer); return new Decision(List.of(), List.of(), true); }
        for (int i = 0; i < received.size(); i++) {
            if (!ProofOfWork.isValid(received.get(i), network))
                throw new BlockHeaderValidationException("Invalid announced proof of work");
            if (i > 0 && !received.get(i).previousBlockHash().equals(received.get(i - 1).hash()))
                throw new IllegalArgumentException("Non-continuous headers response");
        }
        boolean full = received.size() == 2000;
        var state = states.get(peer);
        if (state != null) {
            var result = state.process(received, full);
            if (result.success()) {
                acknowledged.run();
                var locator = result.requestMore() ? nextLocator(state) : List.<Hash256>of();
                if (!result.requestMore()) states.remove(peer);
                // Once the session finishes and releases headers, Core resumes
                // ordinary full-message pagination from the accepted last index.
                return new Decision(result.ready(), locator, result.requestMore() || result.ready().isEmpty());
            }
            states.remove(peer); // A reorg can restart from a different known parent.
        }
        var parent = lookup.find(received.getFirst().previousBlockHash());
        if (parent == null) throw new UnconnectedHeaderException(received.getFirst().hash(), received.getFirst().previousBlockHash());
        acknowledged.run();
        if (peer instanceof ru.bitcoin.node.p2p.Peer connection
                && connection.hasPermission(ru.bitcoin.node.p2p.PeerPermission.NOBAN))
            return new Decision(received, List.of(), false);
        var last = lookup.find(received.getLast().hash());
        if (last != null && (ancestorOf(last, headers.bestHeaderTip()) || ancestorOf(last, active.get())))
            return new Decision(received, List.of(), false);
        var required = threshold(); var work = parent.chainWork();
        for (var header : received) work = work.add(ChainWork.blockWork(header.bits().value()));
        if (work.compareTo(required) >= 0) return new Decision(received, List.of(), false);
        if (!full) return new Decision(List.of(), List.of(), true);
        state = new HeadersPresyncState(network, parent, required, MedianTimePast.calculate(parent, lookup), now.getAsLong());
        var result = state.process(received, true);
        if (!result.success()) return new Decision(List.of(), List.of(), true);
        states.put(peer, state);
        return new Decision(List.of(), nextLocator(state), true);
    }
    private List<Hash256> nextLocator(HeadersPresyncState state) {
        var result = new ArrayList<Hash256>(); result.add(state.nextHash()); result.addAll(locators.build(state.start()));
        return List.copyOf(result);
    }
    private boolean ancestorOf(BlockIndex candidate, BlockIndex tip) {
        if (candidate.height() > tip.height()) return false;
        if (lookup instanceof BlockIndexAncestorLookup ancestors)
            return ancestors.ancestor(tip, candidate.height()).hash().equals(candidate.hash());
        while (tip.height() > candidate.height()) tip = Objects.requireNonNull(lookup.find(tip.previousBlockHash()));
        return tip.hash().equals(candidate.hash());
    }
}
