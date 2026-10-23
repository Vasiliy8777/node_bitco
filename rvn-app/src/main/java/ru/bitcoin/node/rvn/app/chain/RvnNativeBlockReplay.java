package ru.bitcoin.node.rvn.app.chain;

import ru.bitcoin.node.rvn.chain.*;
import ru.bitcoin.node.rvn.storage.*;
import ru.bitcoin.node.script.ScriptVerifyFlags;
import java.io.*;
import java.util.*;

/** Bounded replay of stored bodies through native checks and durable coins.
 * Stops before asset activation. This is not a mining-ready full chainstate. */
public final class RvnNativeBlockReplay {
    private final RvnHeaderStore headers;
    private final RvnCoinStore coins;
    private final RvnAssetsDeployment assets;
    private static final int FLAGS = ScriptVerifyFlags.P2SH | ScriptVerifyFlags.DERSIG
            | ScriptVerifyFlags.CHECKLOCKTIMEVERIFY | ScriptVerifyFlags.CHECKSEQUENCEVERIFY
            | ScriptVerifyFlags.WITNESS | ScriptVerifyFlags.NULLDUMMY;

    public RvnNativeBlockReplay(RvnHeaderStore headers, RvnCoinStore coins) throws IOException {
        this(headers, coins, new RvnAssetsDeployment());
    }

    RvnNativeBlockReplay(RvnHeaderStore headers, RvnCoinStore coins, RvnAssetsDeployment assets) throws IOException {
        this.headers = Objects.requireNonNull(headers); this.coins = Objects.requireNonNull(coins);
        this.assets = Objects.requireNonNull(assets);
        var tip = coins.tip();
        if (headers.require(tip.id()).height() != tip.height()) throw new IOException("RVN coin/header tip mismatch");
    }

    public record Progress(int connected, int disconnected, RvnCoinStore.Tip tip) {}

    /** Per-block atomic commits make restart resume at the last completed block,
     * including when a branch switch was interrupted between disconnects. */
    public Progress advance(int limit) throws IOException {
        if (limit < 1 || limit > 128) throw new IllegalArgumentException("RVN replay batch limit");
        // Keep one header branch and one parent coin view throughout each batch.
        synchronized (headers) { synchronized (coins) {
            int connected = 0, disconnected = 0;
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
                if (assets.activeForNext(parent, headers)) throw new AssetsNotImplementedException(next.height());
                var block = RvnBlockCodec.parse(body);
                if (!Arrays.equals(block.header().serialize(), next.raw()))
                    throw new IOException("RVN replay body/header mismatch");
                RvnBlockBodyChecks.requireValid(block, false);
                RvnCoinbase.requireHeight(block.transactions().getFirst(), next.height());
                long mtp = median(parent);
                var context = new RvnSpendContext(next.height(), mtp, mtp, true, FLAGS, height -> {
                    try { return median(headers.ancestor(parent, height)); }
                    catch (IOException e) { throw new UncheckedIOException(e); }
                });
                var changes = RvnNativeTransactionChecks.block(block, context, false, coins);
                coins.connect(coins.tip(), next.id(), changes);
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
