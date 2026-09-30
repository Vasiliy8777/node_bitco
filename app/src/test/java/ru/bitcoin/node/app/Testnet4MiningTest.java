package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.rpc.MiningController;
import ru.bitcoin.node.app.service.*;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.mining.NonceMiner;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.protocol.network.*;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class Testnet4MiningTest {
    @TempDir Path directory;

    @Test void testnet4BootstrapProducesWorkWithoutTimeRolling() throws Exception {
        var params = new ru.bitcoin.node.app.config.NetworkConfiguration().networkParameters("testnet4");
        long now = 1714777861L;
        try (var db = new RocksDbDatabase(directory); var peers = new PeerManager();
             var node = new NodeValidationService(db, params, () -> now, new Mempool());
             var relay = new NodeRelayService(node, new NodeSyncInfrastructure(db, params, () -> now), peers)) {
            assertEquals(BitcoinNetwork.TESTNET4, params.network());
            assertEquals(List.of("seed.testnet4.bitcoin.sprovoost.nl", "seed.testnet4.wiz.biz"),
                    ru.bitcoin.node.p2p.address.NetworkDnsSeeds.forNetwork(params.network()));
            var work = new StratumMiningBackend(node, relay, params, () -> now, () -> true,
                    new byte[]{0x51}, 4_000_000, new FeeRate(0)).work().orElseThrow();
            assertFalse(work.timeRolling());
            assertEquals(params.genesisBlockHash(), work.block().header().previousBlockHash());
            assertTrue(node.validateBlockProposal(work.block()).valid());
        }
    }

    @Test void templateRpcAndStratumRespectBoundaryMinimumTime() throws Exception {
        var base = NetworkParametersRegistry.regtest();
        // Shortened interval and cheap PoW keep this end-to-end mining test deterministic.
        var params = new NetworkParameters(base.network(), base.magic(), base.defaultPort(), base.genesisBlockHash(),
                null, base.powLimit(), 600, 7200, 150, 1, 1, 1, 1, 0, true, true, true);
        var now = new AtomicLong(1_800_000_000L);
        try (var db = new RocksDbDatabase(directory); var peers = new PeerManager();
             var node = new NodeValidationService(db, params, now::get, new Mempool());
             var relay = new NodeRelayService(node, new NodeSyncInfrastructure(db, params, now::get), peers)) {
            for (int height = 1; height <= 11; height++) {
                now.set(1_800_000_000L + (height == 11 ? 1000 : height));
                var block = node.createMiningTemplate(new byte[]{0x51}, new byte[0], 4_000_000, new FeeRate(0));
                node.processBlock(NonceMiner.search(block, params, 0, 100_000, () -> false).orElseThrow());
            }
            now.set(1_800_000_020L);
            long expected = 1_800_000_400L;
            var snapshot = node.miningSnapshot(new byte[]{0x51}, new byte[0], 4_000_000, new FeeRate(0));
            assertEquals(expected, snapshot.minimumTimestamp());
            assertEquals(expected, snapshot.block().header().timestamp().value());
            assertTrue(node.validateBlockProposal(snapshot.block()).valid());
            var rpc = new MiningController(node, relay, params, () -> true, new byte[]{0x51}, 4_000_000, new FeeRate(0));
            assertEquals(expected, rpc.getBlockTemplate(Map.of("rules", List.of("segwit"))).get("mintime"));
            var work = new StratumMiningBackend(node, relay, params, now::get, () -> true,
                    new byte[]{0x51}, 4_000_000, new FeeRate(0)).work().orElseThrow();
            assertEquals(expected, work.minimumTime());
            var header = snapshot.block().header();
            var early = new ru.bitcoin.node.protocol.block.Block(new ru.bitcoin.node.protocol.block.BlockHeader(
                    header.version(), header.previousBlockHash(), header.merkleRoot(),
                    new ru.bitcoin.node.common.types.UInt32(expected - 1), header.bits(), header.nonce()),
                    snapshot.block().transactions());
            assertFalse(node.validateBlockProposal(early).valid());
            var minedEarly = NonceMiner.search(early, params, 0, 100_000, () -> false).orElseThrow();
            assertThrows(ru.bitcoin.node.consensus.block.BlockHeaderValidationException.class,
                    () -> node.processBlock(minedEarly));
            assertEquals(11, node.activeTip().height());
            assertEquals(ru.bitcoin.node.chain.BlockProcessingResult.CONNECTED, node.processBlock(
                    NonceMiner.search(snapshot.block(), params, 0, 100_000, () -> false).orElseThrow()));
        }
    }
}
