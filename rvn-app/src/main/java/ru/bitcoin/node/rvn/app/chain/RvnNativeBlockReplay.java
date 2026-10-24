package ru.bitcoin.node.rvn.app.chain;

import ru.bitcoin.node.rvn.chain.*;
import ru.bitcoin.node.rvn.storage.*;
import ru.bitcoin.node.script.ScriptVerifyFlags;
import ru.bitcoin.node.rvn.chain.assets.RvnCanonicalAssetBlockChecks;
import ru.bitcoin.node.rvn.chain.assets.RvnHistoricalAssetCodec;
import java.io.*;
import java.util.*;

/** Bounded replay through scripts/assets into durable state. Native-only mode
 * stops before assets. Historical mode records contiguous genesis coverage
 * under its versioned, per-block-flushed qualifier snapshot policy. */
public final class RvnNativeBlockReplay {
    public static final String VALIDATOR_VERSION = "rvn-core-4.8.0-flushed-v1";
    private final RvnHeaderStore headers;
    private final RvnCoinStore coins;
    private final RvnAssetsDeployment assets;
    private final boolean canonicalAssets;
    private final boolean trackValidation;
    private final RvnAssetDeploymentContext assetContext = new RvnAssetDeploymentContext();
    private static final int FLAGS = ScriptVerifyFlags.P2SH | ScriptVerifyFlags.DERSIG
            | ScriptVerifyFlags.CHECKLOCKTIMEVERIFY | ScriptVerifyFlags.CHECKSEQUENCEVERIFY
            | ScriptVerifyFlags.WITNESS | ScriptVerifyFlags.NULLDUMMY;

    public RvnNativeBlockReplay(RvnHeaderStore headers, RvnCoinStore coins) throws IOException {
        this(headers, coins, false);
    }

    public RvnNativeBlockReplay(RvnHeaderStore headers, RvnCoinStore coins, boolean canonicalAssets) throws IOException {
        this(headers, coins, new RvnAssetsDeployment(), canonicalAssets, true);
    }

    RvnNativeBlockReplay(RvnHeaderStore headers, RvnCoinStore coins, RvnAssetsDeployment assets) throws IOException {
        this(headers, coins, assets, false);
    }
    RvnNativeBlockReplay(RvnHeaderStore headers, RvnCoinStore coins, RvnAssetsDeployment assets, boolean canonicalAssets) throws IOException {
        this(headers, coins, assets, canonicalAssets, false);
    }
    private RvnNativeBlockReplay(RvnHeaderStore headers, RvnCoinStore coins, RvnAssetsDeployment assets,
            boolean canonicalAssets, boolean trackValidation) throws IOException {
        this.headers = Objects.requireNonNull(headers); this.coins = Objects.requireNonNull(coins);
        this.assets = Objects.requireNonNull(assets);
        this.canonicalAssets = canonicalAssets;
        this.trackValidation = trackValidation;
        var tip = coins.tip();
        if (headers.require(tip.id()).height() != tip.height()) throw new IOException("RVN coin/header tip mismatch");
    }

    public record Progress(int connected, int disconnected, RvnCoinStore.Tip tip) {}

    /** Actual contiguous genesis-to-tip coverage, never inferred from height alone.
     * Unsupported or invalid blocks cannot advance this versioned watermark. */
    public boolean completeHistoricalValidation() {
        if (!trackValidation || !canonicalAssets) return false;
        synchronized (coins) {
            try {
                var tip = coins.tip();
                return tip.height() >= ru.bitcoin.node.rvn.consensus.RvnMainnetCheckpoints.RECOVERY_HEIGHT
                        && coins.validation().filter(v -> v.version().equals(version()) && v.tip().equals(tip)).isPresent();
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }
    }
    private String version() { return VALIDATOR_VERSION + (canonicalAssets ? "" : "-native"); }

    /** Per-block atomic commits make restart resume at the last completed block,
     * including when a branch switch was interrupted between disconnects. */
    public Progress advance(int limit) throws IOException {
        return advance(limit, null);
    }

    /** Mine admission uses the identical replay checks, with an atomic outbound
     * marker only for this exact next block. Header/body must already be stored. */
    public Progress connectMined(ru.bitcoin.node.rvn.consensus.RvnHash256 id) throws IOException {
        Objects.requireNonNull(id);
        synchronized (headers) { synchronized (coins) {
            var next = headers.best(); var parent = coins.tip();
            if (!next.id().equals(id) || next.height() != parent.height() + 1 || !next.parentId().equals(parent.id()))
                throw new IllegalStateException("RVN mined block is not the next coin tip");
            return advance(1, id);
        } }
    }

    private Progress advance(int limit, ru.bitcoin.node.rvn.consensus.RvnHash256 mined) throws IOException {
        if (limit < 1 || limit > 128) throw new IllegalArgumentException("RVN replay batch limit");
        // Keep one header branch and one parent coin view throughout each batch.
        synchronized (headers) { synchronized (coins) {
            int connected = 0, disconnected = 0;
            if (trackValidation && coins.validation().filter(v -> v.version().equals(version()) && v.tip().equals(coins.tip())).isEmpty()) {
                // An old/imported tip is not coverage under the new rules. Use
                // durable undo, preserving all headers and archived block bodies.
                while (coins.tip().height() > 0 && disconnected < limit) {
                    interrupted(); coins.disconnect(coins.tip()); disconnected++;
                }
                if (coins.tip().height() > 0) return new Progress(0, disconnected, coins.tip());
                coins.anchorValidation(version());
            }
            var best = headers.best();
            var current = headers.require(coins.tip().id());
            var fork = headers.commonAncestor(current, best);
            while (coins.tip().height() > fork.height() && disconnected < limit) {
                interrupted(); coins.disconnect(coins.tip()); disconnected++;
            }
            while (connected + disconnected < limit && coins.tip().height() < best.height()) {
                interrupted();
                var parent = headers.require(coins.tip().id());
                var next = headers.ancestor(best, parent.height() + 1);
                if (!next.parentId().equals(parent.id())) throw new IOException("RVN replay branch mismatch");
                byte[] body = headers.blockBody(next.id());
                if (body == null) break;
                boolean active = assets.activeForNext(parent, headers);
                if (active && !canonicalAssets) throw new AssetsNotImplementedException(next.height());
                var block = RvnBlockCodec.parse(body);
                if (!Arrays.equals(block.header().serialize(), next.raw()))
                    throw new IOException("RVN replay body/header mismatch");
                RvnBlockBodyChecks.requireValid(block, active);
                RvnCoinbase.requireHeight(block.transactions().getFirst(), next.height());
                long mtp = median(parent);
                var context = new RvnSpendContext(next.height(), mtp, mtp, true, FLAGS, height -> {
                    try { return median(headers.ancestor(parent, height)); }
                    catch (IOException e) { throw new UncheckedIOException(e); }
                });
                if (active) {
                    var deployment = assetContext.forNext(parent, headers);
                    var plan = RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(block, context, deployment.activation(),
                            deployment.enforcedReissueValues(), coins, name -> coins.findAsset(name).orElse(null), coins,
                            deployment.transferSizeFix(), deployment.coinbaseAssetCheck());
                    if (trackValidation) coins.connectValidated(coins.tip(), next.id(), plan.coins(), plan.assets(), version(), next.id().equals(mined));
                    else if (next.id().equals(mined)) coins.connectMined(coins.tip(), next.id(), plan.coins(), plan.assets());
                    else coins.connect(coins.tip(), next.id(), plan.coins(), plan.assets());
                } else {
                    var changes = RvnNativeTransactionChecks.block(block, context, false, coins);
                    if (trackValidation) coins.connectValidated(coins.tip(), next.id(), changes, ru.bitcoin.node.rvn.chain.assets.RvnAssetChanges.EMPTY,
                            version(), next.id().equals(mined));
                    else if (next.id().equals(mined)) coins.connectMined(coins.tip(), next.id(), changes, ru.bitcoin.node.rvn.chain.assets.RvnAssetChanges.EMPTY);
                    else coins.connect(coins.tip(), next.id(), changes);
                }
                connected++;
            }
            return new Progress(connected, disconnected, coins.tip());
        } }
    }

    public static final class AssetsNotImplementedException extends IllegalStateException {
        public AssetsNotImplementedException(long height) {
            super("RVN native replay stopped at height " + height + ": assets state validation is not implemented");
        }
    }

    private long median(RvnStoredHeader header) throws IOException {
        long[] times = new long[11]; int count = 0;
        while (true) {
            times[count++] = header.timestamp();
            if (count == times.length || header.height() == 0) break;
            header = headers.require(header.parentId());
        }
        Arrays.sort(times, 0, count); return times[count / 2];
    }

    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("RVN replay interrupted");
    }
}
