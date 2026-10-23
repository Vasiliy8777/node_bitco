package ru.bitcoin.node.rvn.app.chain;

import ru.bitcoin.node.rvn.consensus.*;
import ru.bitcoin.node.rvn.pow.*;
import ru.bitcoin.node.rvn.protocol.*;
import ru.bitcoin.node.rvn.storage.*;
import java.io.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Header-only mainnet admission. Full block/UTXO/assets validation is a separate stage. */
public final class RvnMainnetHeaderChain {
    private final RvnHeaderStore store;
    private final RvnPowRouter router;
    private final LongSupplier clock;
    private final RvnAssetsDeployment assets = new RvnAssetsDeployment();

    public RvnMainnetHeaderChain(RvnHeaderStore store, RvnPowEngine engine, LongSupplier clock) {
        this.store = Objects.requireNonNull(store);
        router = new RvnPowRouter(RvnPowSchedule.mainnet(), Objects.requireNonNull(engine));
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized BatchResult accept(List<RvnBlockHeader> headers) throws IOException {
        if (headers == null || headers.size() > 2000) throw new IllegalArgumentException("RVN header batch limit");
        RvnHash256[] last = {null};
        int added = store.accept(headers.stream().map(RvnBlockHeader::serialize).toList(), (raw, branch) -> {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("RVN admission interrupted");
            var header = RvnBlockHeader.parse(raw, RvnActivation.MAINNET_KAWPOW_TIME);
            var parent = branch.require(new RvnHash256(header.previousHashWire()));
            long height = Math.addExact(parent.height(), 1);
            List<RvnStoredHeader> history = new ArrayList<>(); history.add(parent);
            long expected;
            try {
                expected = RvnMainnetDifficulty.expected(header.timestamp(), distance -> {
                    while (history.size() <= distance) {
                        try { history.add(branch.require(history.getLast().parentId())); }
                        catch (IOException e) { throw new UncheckedIOException(e); }
                    }
                    var ancestor = history.get(distance);
                    return new RvnMainnetDifficulty.Ancestor(ancestor.height(), ancestor.timestamp(), ancestor.bits());
                });
            } catch (UncheckedIOException e) { throw e.getCause(); }
            if (header.compactBits() != expected) throw new SecurityException("Unexpected RVN nBits at " + height);
            List<Long> times = new ArrayList<>(11);
            var cursor = parent;
            for (int i = 0; i < 11; i++) {
                times.add(cursor.timestamp());
                if (cursor.height() == 0) break;
                cursor = branch.require(cursor.parentId());
            }
            times.sort(Long::compare);
            long drift = height >= RvnMainnetDifficulty.DGW_HEIGHT ? 720 : 7200;
            RvnHeaderTimeRules.requireTimestamp(header.timestamp(), times.get(times.size() / 2), clock.getAsLong(), drift);
            if (assets.activeForNext(parent, branch) && header.version() < 0x30000000)
                throw new SecurityException("Outdated RVN assets header version");
            try {
                var proof = router.calculate(height, raw);
                if (!RvnPowDigest.meetsTarget(proof.powDigestWire(), RvnPowDigest.ByteOrder.LITTLE_ENDIAN,
                        header.compactBits(), RvnMainnetDifficulty.POW_LIMIT))
                    throw new SecurityException("Invalid RVN proof of work at " + height);
                last[0] = new RvnHash256(proof.blockIdWire());
                return last[0];
            } catch (RvnPowException e) { throw new SecurityException("RVN PoW calculation failed", e); }
        });
        return new BatchResult(added, last[0], store.best());
    }

    public record BatchResult(int added, RvnHash256 lastId, RvnStoredHeader best) {}
    public RvnStoredHeader best() { return store.best(); }
    public List<byte[]> locators(RvnHash256 tip) throws IOException { return store.locators(tip); }
}
