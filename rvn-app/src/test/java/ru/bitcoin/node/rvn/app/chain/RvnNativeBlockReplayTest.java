package ru.bitcoin.node.rvn.app.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.rvn.chain.*;
import ru.bitcoin.node.rvn.consensus.*;
import ru.bitcoin.node.rvn.protocol.*;
import ru.bitcoin.node.rvn.storage.*;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.app.chain.RvnMainnetHeaderChainTest.*;

/** Synthetic proofs test replay/storage plumbing, not accepted mainnet blocks. */
class RvnNativeBlockReplayTest {
    @Test void importedTipRewindsAndResumesGenesisValidationAfterInterruptedRestart() throws Exception {
        var a1 = next(RvnMainnetExpectedBits.GENESIS_ID, 1, 0); var a2 = next(id(a1), 2, 0); var a3 = next(id(a2), 3, 0);
        try (var headers = open(temp.resolve("headers")); var coins = coins()) {
            accept(headers, a1, a2); save(headers, a1); save(headers, a2);
            var replay = new RvnNativeBlockReplay(headers, coins);
            assertEquals(2, replay.advance(16).connected());
            assertEquals(coins.tip(), coins.validation().orElseThrow().tip());
            accept(headers, a3); save(headers, a3);
            coins.connect(coins.tip(), id(a3), new RvnCoinAccounting.Changes(0, Map.of(), Map.of()));
            assertTrue(coins.validation().isEmpty());
            assertEquals(1, new RvnNativeBlockReplay(headers, coins).advance(1).disconnected());
            assertEquals(2, coins.tip().height()); assertTrue(coins.validation().isEmpty());
        }
        try (var headers = open(temp.resolve("headers")); var coins = coins()) {
            var result = new RvnNativeBlockReplay(headers, coins).advance(16);
            assertEquals(2, result.disconnected()); assertEquals(3, result.connected());
            assertEquals(coins.tip(), coins.validation().orElseThrow().tip());
            assertTrue(coins.validation().orElseThrow().version().startsWith(RvnNativeBlockReplay.VALIDATOR_VERSION));
            assertEquals(3, coins.tip().height());
        }
    }
    @TempDir Path temp;
    private static final RvnPayoutAddress PAYOUT = RvnPayoutAddress.parse("RXBurnXXXXXXXXXXXXXXXXXXXXXXWUo9FV", RvnNetwork.MAINNET);
    private static RvnHash256 id(RvnBlock block) { return new RvnHash256(RvnWire.doubleSha(block.header().serialize())); }
    private static RvnBlock block(RvnHash256 parent, long height, long extraNonce, long timestamp,
                                   java.util.function.UnaryOperator<Transaction> transform) throws Exception {
        var tx = transform.apply(RvnCoinbase.create(height, extraNonce, 0, PAYOUT));
        byte[] raw = child(parent, timestamp);
        raw[0] = 0x44; // signal the small test-only assets deployment too
        System.arraycopy(MerkleTree.calculateRoot(List.of(tx.txId())).bytes(), 0, raw, 36, 32);
        return new RvnBlock(parsed(raw), List.of(tx));
    }
    private RvnBlock next(RvnHash256 parent, long height, long nonce) throws Exception {
        return block(parent, height, nonce, 1514999494L + 60 * height, tx -> tx);
    }
    private static void accept(RvnHeaderStore headers, RvnBlock... blocks) throws Exception {
        new RvnMainnetHeaderChain(headers, TEST_POW, () -> 1515010000L)
                .accept(Arrays.stream(blocks).map(RvnBlock::header).toList());
    }
    private static void save(RvnHeaderStore headers, RvnBlock block) throws Exception {
        headers.saveDownloadedBody(id(block), RvnBlockCodec.serialize(block),
                (bytes, header) -> RvnBlockBodyChecks.requireValid(RvnBlockCodec.parse(bytes), false));
    }
    private RvnCoinStore coins() throws Exception { return new RvnCoinStore(temp.resolve("coins"), RvnMainnetExpectedBits.GENESIS_ID); }

    @Test
    void replayWaitsForMissingBodyAndResumesAfterRestart() throws Exception {
        var first = next(RvnMainnetExpectedBits.GENESIS_ID, 1, 0);
        var second = next(id(first), 2, 0);
        try (var headers = open(temp.resolve("headers")); var coins = coins()) {
            accept(headers, first, second); save(headers, first);
            var replay = new RvnNativeBlockReplay(headers, coins);
            assertEquals(1, replay.advance(8).connected());
            assertEquals(1, coins.tip().height());
            assertEquals(0, replay.advance(8).connected());
            assertTrue(coins.find(new OutPoint(first.transactions().getFirst().txId(), new UInt32(0))).isPresent());
            save(headers, second);
        }
        try (var headers = open(temp.resolve("headers")); var coins = coins()) {
            assertEquals(1, new RvnNativeBlockReplay(headers, coins).advance(8).connected());
            assertEquals(new RvnCoinStore.Tip(id(second), 2), coins.tip());
            assertEquals(0, new RvnNativeBlockReplay(headers, coins).advance(8).connected());
        }
    }

    @Test
    void reorganizationInterruptedAfterDisconnectResumesOnNewBranch() throws Exception {
        var a1 = next(RvnMainnetExpectedBits.GENESIS_ID, 1, 0); var a2 = next(id(a1), 2, 0);
        var b1 = next(RvnMainnetExpectedBits.GENESIS_ID, 1, 1); var b2 = next(id(b1), 2, 1); var b3 = next(id(b2), 3, 1);
        try (var headers = open(temp.resolve("headers")); var coins = coins()) {
            accept(headers, a1, a2); save(headers, a1); save(headers, a2);
            var replay = new RvnNativeBlockReplay(headers, coins);
            assertEquals(2, replay.advance(8).connected());
            accept(headers, b1, b2, b3); save(headers, b1); save(headers, b2); save(headers, b3);
            var progress = replay.advance(1);
            assertEquals(1, progress.disconnected()); assertEquals(id(a1), coins.tip().id());
        }
        try (var headers = open(temp.resolve("headers")); var coins = coins()) {
            var progress = new RvnNativeBlockReplay(headers, coins).advance(8);
            assertEquals(1, progress.disconnected()); assertEquals(3, progress.connected());
            assertEquals(new RvnCoinStore.Tip(id(b3), 3), coins.tip());
            assertTrue(coins.find(new OutPoint(a1.transactions().getFirst().txId(), new UInt32(0))).isEmpty());
            assertTrue(coins.find(new OutPoint(b1.transactions().getFirst().txId(), new UInt32(0))).isPresent());
        }
    }

    @Test
    void coinbaseHeightRewardAndFinalityFailuresDoNotWriteCoins() throws Exception {
        for (int variant = 0; variant < 3; variant++) {
            int selected = variant;
            var bad = block(RvnMainnetExpectedBits.GENESIS_ID, 1, 0, 1514999554L, original -> {
                if (selected == 0) return RvnCoinbase.create(2, 0, 0, PAYOUT);
                if (selected == 1) return new Transaction(1, original.inputs(),
                        List.of(new TxOut(RvnCoinbase.subsidy(1) + 1, PAYOUT.scriptPubKey())), original.lockTime());
                var in = original.inputs().getFirst();
                return new Transaction(1, List.of(new TxIn(in.previousOutput(), in.scriptSig(), new UInt32(0))),
                        original.outputs(), new UInt32(1514999494)); // equals parent's MTP: still non-final
            });
            try (var headers = open(temp.resolve("headers" + variant));
                 var coins = new RvnCoinStore(temp.resolve("coins" + variant), RvnMainnetExpectedBits.GENESIS_ID)) {
                accept(headers, bad); save(headers, bad);
                assertThrows(SecurityException.class, () -> new RvnNativeBlockReplay(headers, coins).advance(8));
                assertEquals(0, coins.tip().height());
                assertTrue(coins.find(new OutPoint(bad.transactions().getFirst().txId(), new UInt32(0))).isEmpty());
            }
        }
    }

    @Test
    void stopsBeforeAssetsActivationWithoutCommittingUnsupportedBlock() throws Exception {
        try (var headers = open(temp.resolve("headers")); var coins = coins()) {
            var parent = RvnMainnetExpectedBits.GENESIS_ID;
            for (int height = 1; height <= 6; height++) {
                var block = next(parent, height, 0); accept(headers, block); save(headers, block); parent = id(block);
            }
            var replay = new RvnNativeBlockReplay(headers, coins, new RvnAssetsDeployment(2, 1, 0, Long.MAX_VALUE));
            assertThrows(RvnNativeBlockReplay.AssetsNotImplementedException.class, () -> replay.advance(8));
            assertEquals(5, coins.tip().height());
            assertThrows(RvnNativeBlockReplay.AssetsNotImplementedException.class, () -> replay.advance(8));
            assertEquals(5, coins.tip().height());
        }
    }

    @Test
    void optInCanonicalReplayCommitsAssetPlansAndReorgRestoresCoinsAndMetadata() throws Exception {
        var source = new OutPoint(new ru.bitcoin.node.common.types.Hash256(new byte[32]), new UInt32(1));
        var fund = new RvnCoin(new TxOut(500 * RvnBlockBodyChecks.COIN, new byte[]{0x51}), 1, false);
        RvnHash256 fork;
        try (var headers = open(temp.resolve("headers")); var coins = coins()) {
            var parent = RvnMainnetExpectedBits.GENESIS_ID;
            for (int height = 1; height <= 5; height++) {
                var block = next(parent, height, 0); accept(headers, block); save(headers, block); parent = id(block);
                // Synthetic storage fixture supplies an already validated native
                // funding coin without requiring 100 historical maturity blocks.
                if (height == 1) coins.connect(coins.tip(), parent, new RvnCoinAccounting.Changes(0, Map.of(), Map.of(source, fund)));
            }
            fork = parent;
            var owner = new ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.Owner("TOKEN!");
            var issuance = new ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.Issue("TOKEN", 10 * RvnBlockBodyChecks.COIN, 0, true, null);
            var burn = RvnPayoutAddress.parse("RXissueAssetXXXXXXXXXXXXXXXXXhhZGt", RvnNetwork.MAINNET);
            var issue = new Transaction(2, List.of(new TxIn(source, new byte[0], TxIn.FINAL_SEQUENCE)), List.of(
                    new TxOut(500 * RvnBlockBodyChecks.COIN, burn.scriptPubKey()),
                    new TxOut(0, ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.encodeCanonical(PAYOUT.scriptPubKey(), owner)),
                    new TxOut(0, ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.encodeCanonical(PAYOUT.scriptPubKey(), issuance))), new UInt32(0));
            var txs = List.of(RvnCoinbase.create(6, 0, 0, PAYOUT), issue);
            byte[] raw = child(parent, 1514999494L + 60 * 6); raw[0] = 0x44;
            System.arraycopy(MerkleTree.calculateRoot(txs.stream().map(Transaction::txId).toList()).bytes(), 0, raw, 36, 32);
            var assetBlock = new RvnBlock(parsed(raw), txs); accept(headers, assetBlock); save(headers, assetBlock);
            var replay = new RvnNativeBlockReplay(headers, coins, new RvnAssetsDeployment(2, 1, 0, Long.MAX_VALUE), true);
            assertEquals(5, replay.advance(16).connected());
            assertEquals(10 * RvnBlockBodyChecks.COIN, coins.findAsset("TOKEN").orElseThrow().supply());
            assertTrue(coins.find(source).isEmpty());
            byte[] historical = ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.encodeCanonical(PAYOUT.scriptPubKey(), issuance);
            historical[26] = (byte) 0xff; // Historical decoding accepts the envelope; this coinbase lacks required burn/owner layout.
            var unsupported = block(id(assetBlock), 7, 0, 1514999494L + 60 * 7, original -> new Transaction(original.version(), original.inputs(),
                    List.of(original.outputs().getFirst(), new TxOut(0, historical)), original.lockTime()));
            accept(headers, unsupported); save(headers, unsupported);
            assertThrows(RuntimeException.class, () -> replay.advance(16));
            assertEquals(6, coins.tip().height()); assertTrue(coins.findAsset("TOKEN").isPresent());
        }
        try (var headers = open(temp.resolve("headers")); var coins = coins()) {
            assertTrue(coins.findAsset("TOKEN").isPresent());
            var parent = fork;
            for (int height = 6; height <= 8; height++) {
                var block = next(parent, height, 9); accept(headers, block); save(headers, block); parent = id(block);
            }
            var replay = new RvnNativeBlockReplay(headers, coins, new RvnAssetsDeployment(2, 1, 0, Long.MAX_VALUE), true);
            var progress = replay.advance(16);
            assertEquals(1, progress.disconnected()); assertEquals(3, progress.connected());
            assertEquals(8, coins.tip().height()); assertTrue(coins.findAsset("TOKEN").isEmpty());
            assertEquals(Optional.of(fund), coins.find(source));
        }
    }

    @Test
    void interruptedReplayAndHeaderCoinHeightMismatchAreRejected() throws Exception {
        var first = next(RvnMainnetExpectedBits.GENESIS_ID, 1, 0);
        try (var headers = open(temp.resolve("headers")); var coins = coins()) {
            accept(headers, first); save(headers, first);
            var replay = new RvnNativeBlockReplay(headers, coins);
            Thread.currentThread().interrupt();
            try { assertThrows(java.io.InterruptedIOException.class, () -> replay.advance(8)); }
            finally { Thread.interrupted(); }
            assertEquals(0, coins.tip().height());
            var second = next(id(first), 2, 0); accept(headers, second);
            // Deliberately inconsistent storage metadata: header height 2, coin tip height 1.
            coins.connect(coins.tip(), id(second), new RvnCoinAccounting.Changes(0, Map.of(), Map.of()));
            assertThrows(java.io.IOException.class, () -> new RvnNativeBlockReplay(headers, coins));
        }
    }

    @Test
    void synchronizationReplaysCachedBodiesBeforeNetworkHandshake() throws Exception {
        var first = next(RvnMainnetExpectedBits.GENESIS_ID, 1, 0);
        try (var headers = open(temp.resolve("headers")); var coins = coins();
             var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            accept(headers, first); save(headers, first);
            var chain = new RvnMainnetHeaderChain(headers, TEST_POW, () -> 1515010000L);
            var service = new ru.bitcoin.node.rvn.app.sync.RvnHeaderSyncService(chain, List.of("127.0.0.1"),
                    server.getLocalPort(), 300, 1000, 1000,
                    new ru.bitcoin.node.rvn.app.sync.RvnBlockDownloader(headers, 1000),
                    new RvnNativeBlockReplay(headers, coins));
            server.setSoTimeout(5000);
            try {
                service.start();
                try (var connection = server.accept()) {
                    assertEquals(new RvnCoinStore.Tip(id(first), 1), coins.tip());
                }
            } finally { service.stop(); }
            assertFalse(service.isRunning());
        }
    }
}
