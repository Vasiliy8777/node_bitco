package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.crypto.hash.Hash160;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.utxo.*;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class NodeValidationServiceTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void graphQueriesDoNotReadUnrelatedTransactionInputs(int mode) {
        var fund = new OutPoint(Hash256.fromDisplayHex("45".repeat(32)), new UInt32(0));
        var otherFund = new OutPoint(Hash256.fromDisplayHex("46".repeat(32)), new UInt32(0));
        var parent = org.mockito.Mockito.spy(spend(fund, 90_000));
        var child = org.mockito.Mockito.spy(spend(new OutPoint(parent.txId(), new UInt32(0)), 80_000));
        var unrelated = org.mockito.Mockito.spy(spend(otherFund, 90_000));
        var pool = new Mempool();
        var context = new ru.bitcoin.node.mempool.MempoolValidationContext(200, 1_800_000_000L, h -> 1_700_000_000L);
        ru.bitcoin.node.consensus.transaction.UtxoView coins = point -> point.equals(fund) || point.equals(otherFund)
                ? Optional.of(new ru.bitcoin.node.consensus.transaction.UtxoEntry(100_000, SCRIPT, 0, false)) : Optional.empty();
        pool.admit(parent, context, coins); pool.admit(child, context, coins); pool.admit(unrelated, context, coins);
        org.mockito.Mockito.clearInvocations(unrelated);
        switch (mode) {
            case 0 -> assertEquals(Set.of(parent.txId()), pool.graphView(child.txId()).orElseThrow().parents());
            case 1 -> assertEquals(Set.of(parent.txId()), new HashSet<>(pool.ancestorQuery(child.txId()).orElseThrow().txIds()));
            case 2 -> assertEquals(Set.of(child.txId()), new HashSet<>(pool.descendantQuery(parent.txId()).orElseThrow().txIds()));
            case 3 -> assertEquals(2, pool.cluster(child.txId()).orElseThrow().transactionCount());
            case 4 -> assertEquals(Set.of(parent.txId()), new HashSet<>(pool.ancestors(child.txId()).orElseThrow()));
            case 5 -> assertEquals(Set.of(child.txId()), new HashSet<>(pool.descendants(parent.txId()).orElseThrow()));
            case 6 -> {
                org.mockito.Mockito.clearInvocations(parent, child);
                pool.graphViews(List.of(parent.txId()));
                long single = graphInputCalls(parent, child);
                org.mockito.Mockito.clearInvocations(parent, child);
                pool.graphViews(List.of(parent.txId(), child.txId()));
                assertEquals(single, graphInputCalls(parent, child), "A component must be linearized once per batch");
            }
            default -> throw new AssertionError(mode);
        }
        org.mockito.Mockito.verify(unrelated, org.mockito.Mockito.never()).inputs();
    }

    private static long graphInputCalls(Transaction... transactions) {
        return Arrays.stream(transactions).mapToLong(transaction -> org.mockito.Mockito.mockingDetails(transaction)
                .getInvocations().stream().filter(call -> call.getMethod().getName().equals("inputs")).count()).sum();
    }
    @Test void knownPackageCommitDoesNotRescanUnrelatedTransactionInputs() {
        var firstFund = new OutPoint(Hash256.fromDisplayHex("43".repeat(32)), new UInt32(0));
        var otherFund = new OutPoint(Hash256.fromDisplayHex("44".repeat(32)), new UInt32(0));
        var known = spend(firstFund, 90_000);
        var unrelated = org.mockito.Mockito.spy(spend(otherFund, 90_000));
        var pool = new Mempool();
        var context = new ru.bitcoin.node.mempool.MempoolValidationContext(200, 1_800_000_000L, h -> 1_700_000_000L);
        ru.bitcoin.node.consensus.transaction.UtxoView coins = point -> point.equals(firstFund) || point.equals(otherFund)
                ? Optional.of(new ru.bitcoin.node.consensus.transaction.UtxoEntry(100_000, SCRIPT, 0, false)) : Optional.empty();
        var accepted = pool.admit(known, context, coins); pool.admit(unrelated, context, coins);
        var before = pool.detailedSnapshot();
        org.mockito.Mockito.clearInvocations(unrelated);
        assertEquals(List.of(accepted), pool.admitPackage(List.of(known), context, coins));
        org.mockito.Mockito.verify(unrelated, org.mockito.Mockito.never()).inputs();
        assertEquals(before, pool.detailedSnapshot());
    }
    @Test void knownTransactionProbeDoesNotRescanInputsWhileCloningPool() {
        var fund = new OutPoint(Hash256.fromDisplayHex("42".repeat(32)), new UInt32(0));
        var transaction = org.mockito.Mockito.spy(spend(fund, 90_000));
        var pool = new Mempool();
        var context = new ru.bitcoin.node.mempool.MempoolValidationContext(200, 1_800_000_000L, h -> 1_700_000_000L);
        ru.bitcoin.node.consensus.transaction.UtxoView coins = point -> point.equals(fund)
                ? Optional.of(new ru.bitcoin.node.consensus.transaction.UtxoEntry(100_000, SCRIPT, 0, false)) : Optional.empty();
        pool.admit(transaction, context, coins);
        transaction.wtxId();
        var before = pool.detailedSnapshot();
        org.mockito.Mockito.clearInvocations(transaction);
        assertEquals("txn-already-in-mempool", pool.testAccept(List.of(transaction), context, coins).rejectReason());
        org.mockito.Mockito.verify(transaction, org.mockito.Mockito.never()).inputs();
        assertEquals(before, pool.detailedSnapshot());
    }
    @Test void unchangedGraphReadsDoNotRevisitTransactionInputs() {
        var fund = new OutPoint(Hash256.fromDisplayHex("41".repeat(32)), new UInt32(0));
        var transaction = org.mockito.Mockito.spy(spend(fund, 90_000));
        var pool = new Mempool();
        pool.admit(transaction, new ru.bitcoin.node.mempool.MempoolValidationContext(200, 1_800_000_000L, h -> 1_700_000_000L),
                point -> point.equals(fund) ? Optional.of(new ru.bitcoin.node.consensus.transaction.UtxoEntry(100_000, SCRIPT, 0, false)) : Optional.empty());
        var diagram = pool.feeRateDiagram();
        org.mockito.Mockito.clearInvocations(transaction);
        assertEquals(diagram, pool.feeRateDiagram());
        pool.miningSnapshot();
        org.mockito.Mockito.verify(transaction, org.mockito.Mockito.never()).inputs();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    void broadcastEarlyExitDoesNotExpireExistingGraph(int mode) {
        var seconds = new java.util.concurrent.atomic.AtomicLong(1000);
        var clock = new java.time.Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public java.time.Clock withZone(java.time.ZoneId zone) { Objects.requireNonNull(zone); return this; }
            @Override public java.time.Instant instant() { return java.time.Instant.ofEpochSecond(seconds.get()); }
        };
        var limits = new ru.bitcoin.node.mempool.MempoolLimits(64, 101000, 300000000, 10, 100);
        var pool = new Mempool(new ru.bitcoin.node.mempool.MempoolPolicy(), limits, clock);
        try (var db = new RocksDbDatabase(directory)) {
            var service = new NodeValidationService(db, PARAMS, () -> 1_800_000_000L, pool);
            var coins = new RocksDbUtxoStore(db);
            var fund = new OutPoint(Hash256.fromDisplayHex("38".repeat(32)), new UInt32(0));
            coins.save(fund, new StoredUtxo(100_000, SCRIPT, 0, false));
            var parent = spend(fund, 90_000);
            var parentOutput = new OutPoint(parent.txId(), new UInt32(0));
            var child = spend(parentOutput, 80_000);
            service.admitForBroadcast(parent, 0);
            service.admitForBroadcast(child, 0);
            var snapshot = pool.detailedSnapshot();
            var unbroadcast = pool.unbroadcastTransactions();
            long floor = pool.minimumFeeRate();
            long revision = service.revision();
            seconds.set(1020); // Both entries are old enough to expire, but no commit is requested.
            if (mode == 0) {
                var replacement = spend(fund, 70_000);
                var rejected = assertThrows(NodeValidationService.BroadcastRejection.class,
                        () -> service.admitForBroadcast(replacement, 1));
                assertEquals(NodeValidationService.BroadcastRejectionReason.MAX_FEE, rejected.reason());
            } else if (mode == 1) {
                assertFalse(service.admitForBroadcast(parent, 1).newlyAccepted());
            } else if (mode == 2) {
                var confirmed = spend(new OutPoint(Hash256.fromDisplayHex("39".repeat(32)), new UInt32(0)), 90_000);
                coins.save(new OutPoint(confirmed.txId(), new UInt32(0)), new StoredUtxo(90_000, SCRIPT, 0, false));
                var rejected = assertThrows(NodeValidationService.BroadcastRejection.class,
                        () -> service.admitForBroadcast(confirmed, 1));
                assertEquals(NodeValidationService.BroadcastRejectionReason.CONFIRMED, rejected.reason());
            } else if(mode==3 || mode==4) {
                var missing = spend(new OutPoint(Hash256.fromDisplayHex("40".repeat(32)), new UInt32(0)), 90_000);
                var rejected = assertThrows(ru.bitcoin.node.mempool.MempoolAdmissionException.class,
                        () -> service.admitForBroadcast(missing, mode==3?1:0));
                assertTrue(rejected.isMissingInputs());
            } else if(mode==5) {
                var bad=new Transaction(2,parent.inputs(),List.of(new TxOut(-1,SCRIPT)),parent.lockTime());
                assertThrows(ru.bitcoin.node.consensus.transaction.TransactionValidationException.class,
                        ()->service.admitForBroadcast(bad,0));
            } else if(mode==6) {
                var input=parent.inputs().getFirst();
                var bad=new Transaction(2,List.of(new TxIn(input.previousOutput(),new byte[]{1,0},input.sequence())),List.of(new TxOut(70000,SCRIPT)),parent.lockTime());
                assertThrows(ru.bitcoin.node.script.ScriptExecutionException.class,()->service.admitForBroadcast(bad,0));
            } else if(mode==7) {
                var bad=new Transaction(2,parent.inputs(),List.of(new TxOut(-1,SCRIPT)),parent.lockTime());
                assertEquals("transaction failed",service.submitPackage(List.of(bad),0).packageMessage());
            } else {
                assertEquals(2,service.mempoolEntries().size());
                service.mempoolSnapshot();service.detailedMempoolSnapshot();
                service.mempoolEntry(parent.txId());service.mempoolAncestorQuery(child.txId());
                service.mempoolDescendantQuery(parent.txId());service.mempoolGraphEntries(List.of(parent.txId(),child.txId()));
                service.mempoolCluster(parent.txId());service.mempoolGraphEntry(child.txId());
                service.mempoolFeeRateDiagram();service.feeFilterRate();
            }
            assertEquals(revision,service.revision(),"Read/early rejection must not publish a mempool change");
            assertEquals(snapshot, pool.detailedSnapshot(), "Early broadcast result must preserve entries, graph and sequence");
            assertEquals(unbroadcast, pool.unbroadcastTransactions());
            assertEquals(floor, pool.minimumFeeRate());
            assertEquals(parent.txId(), pool.spendingTransaction(fund).orElseThrow().txId());
            assertEquals(child.txId(), pool.spendingTransaction(parentOutput).orElseThrow().txId());
            assertEquals(2, pool.expire(), "Explicit expiry must still remove the old family");
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void broadcastPreflightFailureStopsWithoutAdmissionOrUnbroadcast(boolean internalFailure) {
        try (var db = new RocksDbDatabase(directory)) {
            var service = org.mockito.Mockito.spy(new NodeValidationService(db, PARAMS, () -> 1_800_000_000L, new Mempool()));
            var tx = spend(new OutPoint(Hash256.fromDisplayHex("36".repeat(32)), new UInt32(0)), 90_000);
            RuntimeException failure = internalFailure ? new IllegalStateException("preflight storage failure")
                    : new ru.bitcoin.node.mempool.MempoolAdmissionException("preflight policy failure");
            org.mockito.Mockito.doThrow(failure).when(service).testBroadcastTransaction(tx);
            assertSame(failure, assertThrows(RuntimeException.class, () -> service.admitForBroadcast(tx, 1_000_000)));
            org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).admit(org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).markMempoolUnbroadcast(org.mockito.ArgumentMatchers.any());
            assertTrue(service.mempoolEntries().isEmpty());
            assertEquals(0, service.unbroadcastMempoolCount());
        }
    }

    @Test void broadcastProbePreservesPoolAndDoesNotRegisterUnbroadcast() {
        try (var db = new RocksDbDatabase(directory)) {
            var service = new NodeValidationService(db, PARAMS, () -> 1_800_000_000L, new Mempool());
            var fund = new OutPoint(Hash256.fromDisplayHex("37".repeat(32)), new UInt32(0));
            new RocksDbUtxoStore(db).save(fund, new StoredUtxo(100_000, SCRIPT, 0, false));
            var tx = spend(fund, 90_000);
            assertEquals(tx.txId(), service.testBroadcastTransaction(tx).transaction().txId());
            assertTrue(service.mempoolEntries().isEmpty());
            assertEquals(0, service.unbroadcastMempoolCount());
            assertTrue(service.admitForBroadcast(tx, 1_000_000).newlyAccepted());
        }
    }
    @Test
    void broadcastKeepsChainMonitorFromFeeProbeThroughCommit() throws Exception {
        try (var db = new RocksDbDatabase(directory)) {
            var service = org.mockito.Mockito.spy(new NodeValidationService(db, PARAMS, () -> 1_800_000_000L, new Mempool()));
            var fund = new OutPoint(Hash256.fromDisplayHex("35".repeat(32)), new UInt32(0));
            new RocksDbUtxoStore(db).save(fund, new StoredUtxo(100_000, SCRIPT, 0, false));
            var tx = spend(fund, 90_000);
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var monitorField = NodeValidationService.class.getDeclaredField("chain");
            monitorField.setAccessible(true);
            Object chainMonitor = monitorField.get(service);
            org.mockito.Mockito.doAnswer(invocation -> {
                assertTrue(Thread.holdsLock(chainMonitor), "Admission must retain the outer broadcast monitor");
                return invocation.callRealMethod();
            }).when(service).admit(org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.doAnswer(invocation -> {
                assertTrue(Thread.holdsLock(chainMonitor), "Unbroadcast registration must be in the same critical section");
                return invocation.callRealMethod();
            }).when(service).markMempoolUnbroadcast(org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.doAnswer(invocation -> {
                entered.countDown();
                assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS));
                return invocation.callRealMethod();
            }).when(service).testBroadcastTransaction(org.mockito.ArgumentMatchers.any());
            var result = new java.util.concurrent.CompletableFuture<NodeValidationService.BroadcastAdmission>();
            Thread broadcaster = Thread.ofPlatform().start(() -> {
                try { result.complete(service.admitForBroadcast(tx, 1_000_000)); }
                catch (Throwable failure) { result.completeExceptionally(failure); }
            });
            var mutation = new java.util.concurrent.CompletableFuture<Void>();
            Thread contender = null;
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                contender = Thread.ofPlatform().start(() -> {
                    try { service.prioritiseTransaction(tx.txId(), 123); mutation.complete(null); }
                    catch (Throwable failure) { mutation.completeExceptionally(failure); }
                });
                int monitor = System.identityHashCode(chainMonitor);
                var threads = java.lang.management.ManagementFactory.getThreadMXBean();
                java.lang.management.ThreadInfo info;
                long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                do {
                    info = threads.getThreadInfo(contender.threadId());
                    if (info != null && info.getThreadState() == Thread.State.BLOCKED
                            && info.getLockInfo().getIdentityHashCode() == monitor) break;
                    Thread.sleep(1);
                } while (System.nanoTime() < end && !mutation.isDone());
                assertNotNull(info);
                assertEquals(Thread.State.BLOCKED, info.getThreadState());
                assertEquals(monitor, info.getLockInfo().getIdentityHashCode());
                assertEquals(broadcaster.threadId(), info.getLockOwnerId());
            } finally {
                release.countDown();
                broadcaster.join(5000);
                if (contender != null) contender.join(5000);
            }
            assertTrue(result.get(5, java.util.concurrent.TimeUnit.SECONDS).newlyAccepted());
            mutation.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(10_123, service.mempoolEntry(tx.txId()).orElseThrow().modifiedFee());
            assertFalse(service.admitForBroadcast(tx, 1).newlyAccepted(), "Known txid bypasses cap inside the same monitor");
        }
    }

    @Test void locatorUsesFirstMatchAndGenesisFallbackWithoutSortingPeerInput() {
        try (var db = new RocksDbDatabase(directory)) {
            var service = new NodeValidationService(db, PARAMS, () -> 1_800_000_000L, new Mempool());
            var genesis = service.activeTip();
            var first = block(genesis, 1, List.of()); service.processBlock(first);
            var second = block(service.activeTip(), 2, List.of()); service.processBlock(second);
            var unknown = Hash256.fromDisplayHex("21".repeat(32));
            assertEquals(Optional.of(genesis.hash()), service.bestActiveLocator(List.of(unknown, genesis.hash(), second.hash())));
            assertEquals(Optional.of(second.hash()), service.bestActiveLocator(List.of(second.hash(), first.hash())));
            assertEquals(Optional.of(genesis.hash()), service.bestActiveLocator(List.of(unknown)));
            assertEquals(Optional.of(genesis.hash()), service.bestActiveLocator(List.of()));
            assertEquals(List.of(first.header(), second.header()), service.headers(List.of(genesis.hash(), second.hash()), unknown));
        }
    }

    @Test void locatorRecognizesHeaderDescendantOfActiveTipButSkipsCompetingBranch() {
        try (var db = new RocksDbDatabase(directory)) {
            var service = new NodeValidationService(db, PARAMS, () -> 1_800_000_000L, new Mempool());
            var genesis = service.activeTip();
            var first = block(genesis, 1, List.of()); service.processBlock(first);
            var ahead = block(service.activeTip(), 2, List.of());
            var side = block(genesis, 3, List.of());
            var side2 = block(BlockIndexFactory.createChild(genesis, side.header()), 4, List.of());
            var side3 = block(BlockIndexFactory.createChild(BlockIndexFactory.createChild(genesis, side.header()), side2.header()), 5, List.of());
            var sync = new ru.bitcoin.node.app.sync.NodeSyncInfrastructure(db, PARAMS, () -> 1_800_000_000L);
            service.processHeaders(sync.headerSyncService(), new ru.bitcoin.node.p2p.message.HeadersMessage(List.of(ahead.header())));
            service.processHeaders(sync.headerSyncService(), new ru.bitcoin.node.p2p.message.HeadersMessage(List.of(side.header(), side2.header(), side3.header())));
            assertEquals(Optional.of(first.hash()), service.bestActiveLocator(List.of(side3.hash(), ahead.hash(), genesis.hash())));
            assertEquals(Optional.of(genesis.hash()), service.bestActiveLocator(List.of(side3.hash(), genesis.hash(), first.hash())));
            assertEquals(List.of(), service.headers(List.of(ahead.hash(), genesis.hash()), new Hash256(new byte[32])));
            assertEquals(first.hash(), service.activeTip().hash(), "Locator handling must not activate header-only descendants");
        }
    }
    @Test
    void publishedIbdStateReflectsPersistedTipImmediatelyAfterStartup() {
        long genesisTime = GenesisBlockFactory.create(PARAMS).header().timestamp().value();
        try (var db = new RocksDbDatabase(directory)) {
            var service = new NodeValidationService(db, PARAMS, () -> genesisTime, new Mempool());
            assertFalse(service.downloadInitialBlockDownload());
        }
        try (var db = new RocksDbDatabase(directory)) {
            var restarted = new NodeValidationService(db, PARAMS, () -> genesisTime, new Mempool());
            assertFalse(restarted.downloadInitialBlockDownload());
        }
    }

    @Test
    void disabledSpeculativePrefetchStillConnectsDependentTransactions() {
        try (var db = new RocksDbDatabase(directory.resolve("no-prefetch"))) {
            var service = new NodeValidationService(db, PARAMS, () -> 1_800_000_000L, new Mempool());
            service.initialSyncPrefetchEnabled(false);
            var fund = new OutPoint(Hash256.fromDisplayHex("14".repeat(32)), new UInt32(0));
            var coins = new RocksDbUtxoStore(db);
            coins.save(fund, new StoredUtxo(100_000, SCRIPT, 0, false));
            var parent = spend(fund, 90_000);
            var child = spend(new OutPoint(parent.txId(), new UInt32(0)), 80_000);
            var first = block(service.activeTip(), 1, List.of(parent, child));
            assertEquals(List.of(BlockProcessingResult.CONNECTED), service.processInitialSyncBatch(List.of(first)));
            assertTrue(coins.find(new OutPoint(child.txId(), new UInt32(0))).isPresent());
            assertTrue(coins.find(fund).isEmpty());
        }
    }

    @Test
    void batchPrefetchSkipsEarlierOutputsButRetainsMissingAndForwardReferences() {
        var fund = new OutPoint(Hash256.fromDisplayHex("12".repeat(32)), new UInt32(0));
        var parent = spend(fund, 90_000);
        var parentOutput = new OutPoint(parent.txId(), new UInt32(0));
        var child = spend(parentOutput, 80_000);
        var missing = new OutPoint(parent.txId(), new UInt32(99));
        var invalidChild = spend(missing, 70_000);
        var header = GenesisBlockFactory.create(PARAMS).header();
        assertEquals(Set.of(fund, missing), NodeValidationService.initialSyncExternalInputs(
                List.of(new Block(header, List.of(parent, child)), new Block(header, List.of(invalidChild)))));
        assertEquals(Set.of(fund, parentOutput), NodeValidationService.initialSyncExternalInputs(
                List.of(new Block(header, List.of(child, parent)))));
    }

    @Test
    void prefetchedBatchConnectsDependentTransactionsAndRejectsDoubleSpend() {
        try (var db = new RocksDbDatabase(directory)) {
            var service = new NodeValidationService(db, PARAMS, () -> 1_800_000_000L, new Mempool());
            var fund = new OutPoint(Hash256.fromDisplayHex("13".repeat(32)), new UInt32(0));
            var coins = new RocksDbUtxoStore(db);
            coins.save(fund, new StoredUtxo(100_000, SCRIPT, 0, false));
            var parent = spend(fund, 90_000);
            var child = spend(new OutPoint(parent.txId(), new UInt32(0)), 80_000);
            var first = block(service.activeTip(), 1, List.of(parent, child));
            assertEquals(List.of(BlockProcessingResult.CONNECTED), service.processInitialSyncBatch(List.of(first)));
            assertTrue(coins.find(new OutPoint(child.txId(), new UInt32(0))).isPresent());
            assertTrue(coins.find(fund).isEmpty());
            var second = block(service.activeTip(), 2, List.of(spend(fund, 70_000)));
            assertThrows(RuntimeException.class, () -> service.processInitialSyncBatch(List.of(second)));
            assertEquals(first.hash(), service.activeTip().hash());
        }
    }

    @TempDir
    Path directory;
    private static final NetworkParameters PARAMS = NetworkParametersRegistry.regtest();
    private static final byte[] SCRIPT = HexFormat.of().parseHex("a914" + HexFormat.of().formatHex(Hash160.hash(new byte[]{0x51})) + "87");

    @Test
    void cachedBlockAndHeaderQueriesFollowReorgAndHonorStopHash() {
        try (var db = new RocksDbDatabase(directory)) {
            var service = new NodeValidationService(db, PARAMS, () -> 1_800_000_000L, new Mempool());
            var genesis = service.activeTip();
            var main = block(genesis, 1, List.of());
            service.processBlock(main);
            assertTrue(service.findBlock(main.hash()).isPresent());
            assertEquals(List.of(main.header()), service.headers(List.of(genesis.hash()), new Hash256(new byte[32])));
            var side = block(genesis, 2, List.of());
            service.processBlock(side);
            assertTrue(service.findBlock(side.hash()).isEmpty());
            var second = block(BlockIndexFactory.createChild(genesis, side.header()), 3, List.of());
            service.processBlock(second);
            assertTrue(service.findBlock(main.hash()).isEmpty());
            assertTrue(service.findBlock(side.hash()).isPresent());
            assertEquals(1, service.activeBlockDepth(side.hash()).orElseThrow());
            assertTrue(service.activeBlockDepth(main.hash()).isEmpty());
            assertEquals(List.of(side.header()), service.headers(List.of(main.hash(), genesis.hash()), side.hash()));
            assertEquals(List.of(side.header(), second.header()), service.headers(List.of(genesis.hash()), new Hash256(new byte[32])));
            assertEquals(Optional.of(genesis.hash()), service.bestActiveLocator(List.of(genesis.hash(), second.hash())));
            assertEquals(Optional.of(second.hash()), service.bestActiveLocator(List.of(second.hash(), genesis.hash())));
        }
    }

    @Test
    void confirmationAndReorgAutomaticallyUpdateMempoolIncludingChildren() {
        try (var db = new RocksDbDatabase(directory)) {
            var service = new NodeValidationService(db, PARAMS, () -> 1_800_000_000L, new Mempool());
            var genesis = service.activeTip();
            OutPoint fund = new OutPoint(Hash256.fromDisplayHex("11".repeat(32)), new UInt32(0));
            new RocksDbUtxoStore(db).save(fund, new StoredUtxo(100_000, SCRIPT, 0, false));
            var parent = spend(fund, 90_000);
            var child = spend(new OutPoint(parent.txId(), new UInt32(0)), 80_000);
            service.admit(parent);
            service.admit(child);
            assertEquals(2, service.mempoolEntries().size());
            var main = block(genesis, 1, List.of(parent));
            assertEquals(BlockProcessingResult.CONNECTED, service.processBlock(main));
            assertEquals(List.of(child.txId()), service.mempoolEntries().stream().map(e -> e.transaction().txId()).toList());
            var side = block(genesis, 2, List.of());
            assertEquals(BlockProcessingResult.STORED_SIDE_CHAIN_CONTEXT_PENDING, service.processBlock(side));
            var sideIndex = BlockIndexFactory.createChild(genesis, side.header());
            assertEquals(BlockProcessingResult.CONNECTED, service.processBlock(block(sideIndex, 3, List.of())));
            assertEquals(Set.of(parent.txId(), child.txId()), new HashSet<>(service.mempoolEntries().stream().map(e -> e.transaction().txId()).toList()));
        }
    }

    @Test
    void restoresPersistedMempoolInDependencyOrderAfterRestart() {
        OutPoint fund = new OutPoint(Hash256.fromDisplayHex("22".repeat(32)), new UInt32(0));
        Transaction parent;
        Transaction child;
        try (var db = new RocksDbDatabase(directory)) {
            var service = new NodeValidationService(db, PARAMS, () -> 1_800_000_000L, new Mempool());
            new RocksDbUtxoStore(db).save(fund, new StoredUtxo(100_000, SCRIPT, 0, false));
            parent = spend(fund, 90_000);
            child = spend(new OutPoint(parent.txId(), new UInt32(0)), 80_000);
            service.admit(parent);
            service.admit(child);
            assertEquals(2, service.mempoolEntries().size());
            assertTrue(service.isMempoolPersistenceDirty());
            service.flushPersistentMempool();
            assertFalse(service.isMempoolPersistenceDirty());
        }
        try (var db = new RocksDbDatabase(directory)) {
            var pool = org.mockito.Mockito.spy(new Mempool());
            var restored = new NodeValidationService(db, PARAMS, () -> 1_800_000_000L, pool);
            assertEquals(List.of(parent.txId(), child.txId()),
                    restored.mempoolEntries().stream().map(e -> e.transaction().txId()).toList());
            org.mockito.Mockito.verify(pool, org.mockito.Mockito.never()).revalidate(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anySet());
        }
    }

    @Test
    void suppliedMempoolIsStillValidatedAgainstStartupChain() {
        var fund = new OutPoint(Hash256.fromDisplayHex("24".repeat(32)), new UInt32(0));
        var tx = spend(fund, 90_000);
        var pool = new Mempool();
        pool.admit(tx, new ru.bitcoin.node.mempool.MempoolValidationContext(200, 1_800_000_000L, h -> 0),
                point -> point.equals(fund)
                        ? Optional.of(new ru.bitcoin.node.consensus.transaction.UtxoEntry(100_000, SCRIPT, 0, false))
                        : Optional.empty());
        assertEquals(1, pool.size());
        try (var db = new RocksDbDatabase(directory)) {
            // The new chain has no funding coin: the supplied entry must not survive startup.
            var service = new NodeValidationService(db, PARAMS, () -> 1_800_000_000L, pool);
            assertTrue(service.mempoolEntries().isEmpty());
            assertTrue(pool.spendingTransaction(fund).isEmpty());
        }
    }

    @Test
    void mempoolMutationsRemainInMemoryUntilExplicitPersistenceFlush() {
        OutPoint fund = new OutPoint(Hash256.fromDisplayHex("33".repeat(32)), new UInt32(0));
        Transaction transaction;

        try (var db = new RocksDbDatabase(directory)) {
            var service = new NodeValidationService(db, PARAMS, () -> 1_800_000_000L, new Mempool());
            new RocksDbUtxoStore(db).save(fund, new StoredUtxo(100_000, SCRIPT, 0, false));
            transaction = spend(fund, 90_000);
            service.admit(transaction);
            assertTrue(service.isMempoolPersistenceDirty());

            // No per-transaction RocksDB persistence anymore.
            assertTrue(new ru.bitcoin.node.storage.mempool.RocksDbMempoolStore(db).load().isEmpty());

            service.flushPersistentMempool();
            assertFalse(service.isMempoolPersistenceDirty());
            assertEquals(List.of(transaction.txId()),
                    new ru.bitcoin.node.storage.mempool.RocksDbMempoolStore(db).load().stream()
                            .map(entry -> entry.transaction().txId()).toList());
        }
    }

    @Test
    void exposesPruneModeSeparatelyFromWhetherAnythingHasBeenPruned() {
        try (var db = new RocksDbDatabase(directory)) {
            var full = new NodeValidationService(db, PARAMS, () -> 1_800_000_000L, new Mempool());
            assertFalse(full.pruneInfo().enabled());
            assertFalse(full.pruneInfo().hasPruned());
            assertEquals(0L, full.pruneInfo().targetBytes());
        }
        try (var db = new RocksDbDatabase(directory)) {
            var pruned = new NodeValidationService(db, PARAMS, () -> 1_800_000_000L, new Mempool(), 550L * 1024L * 1024L);
            assertTrue(pruned.pruneInfo().enabled());
            assertFalse(pruned.pruneInfo().hasPruned());
            assertEquals(550L * 1024L * 1024L, pruned.pruneInfo().targetBytes());
            assertEquals(0L, pruned.pruneInfo().pruneHeight());
        }
        try (var db = new RocksDbDatabase(directory)) {
            var manual = new NodeValidationService(db, PARAMS, () -> 1_800_000_000L, new Mempool(), BlockPruner.MANUAL_ONLY);
            assertTrue(manual.pruneInfo().enabled());
            assertFalse(manual.pruneInfo().automatic());
            assertFalse(manual.pruneInfo().hasPruned());
            assertEquals(0L, manual.pruneInfo().targetBytes());
        }
    }

    private static Transaction spend(OutPoint point, long value) {
        return new Transaction(2, List.of(new TxIn(point, new byte[]{1, 0x51}, TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(value, SCRIPT)), new UInt32(0));
    }

    private static Block block(BlockIndex parent, int tag, List<Transaction> spends) {
        var coinbase = new Transaction(1, List.of(new TxIn(OutPoint.coinbase(),
                new byte[]{(byte) (0x51 + parent.height()), (byte) tag}, TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(5_000_000_000L, new byte[]{0x51})), new UInt32(0));
        List<Transaction> txs = new ArrayList<>();
        txs.add(coinbase);
        txs.addAll(spends);
        var root = MerkleTree.calculateRoot(txs.stream().map(Transaction::txId).toList());
        for (long nonce = 0; nonce < 100_000; nonce++) {
            var header = new BlockHeader(4, parent.hash(), root, new UInt32(parent.header().timestamp().value() + 1), new UInt32(0x207fffffL), new UInt32(nonce));
            if (ProofOfWork.isValid(header, PARAMS)) return new Block(header, txs);
        }
        throw new AssertionError("Could not mine regtest header");
    }
}
