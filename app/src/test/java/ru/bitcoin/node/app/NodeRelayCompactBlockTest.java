package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.rpc.MiningController;
import ru.bitcoin.node.app.service.NodeRelayService;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.mempool.FeeRate;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeRelayCompactBlockTest {
    @TempDir
    Path directory;

    @Test
    void highBandwidthPeerGetsCompactAnnouncementAndCanRequestTransactions() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(directory.resolve("bip152")); var peers = new PeerManager()) {
            var validation = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool());
            var sync = new NodeSyncInfrastructure(db, parameters, () -> 1_800_000_000L);
            var peer = mock(Peer.class);
            when(peer.isReady()).thenReturn(true);
            var incoming = new AtomicReference<PeerMessageListener>();
            doAnswer(i -> {
                incoming.set(i.getArgument(0));
                return null;
            }).when(peer).addMessageListener(any());
            var outbound = new LinkedBlockingQueue<BitcoinMessage>();
            doAnswer(i -> {
                outbound.add(i.getArgument(0));
                return null;
            }).when(peer).send(any());
            peers.add(peer);

            try (var relay = new NodeRelayService(validation, sync, peers)) {
                incoming.get().onMessage(peer, BitcoinMessages.sendCmpct(new SendCmpctMessage(true, 2)));

                var mining = new MiningController(validation, relay, parameters, () -> true,
                        new byte[]{0x51}, 4_000_000, new FeeRate(0));
                Map<?, ?> template = mining.getBlockTemplate(Map.of("rules", List.of("segwit")));
                Block mined = MiningRpcTest.mineTemplate(template);
                assertEquals(ru.bitcoin.node.chain.BlockProcessingResult.CONNECTED, relay.submitBlock(mined));

                BitcoinMessage compactWire = take(outbound);
                assertEquals("cmpctblock", compactWire.command());
                CompactBlockMessage compact = BitcoinMessages.decodeCompactBlock(compactWire, 2);
                assertEquals(mined.hash(), compact.header().hash());
                assertEquals(mined.transactions().size(), compact.transactionCount());
                assertEquals(0, compact.prefilledTransactions().getFirst().index());

                incoming.get().onMessage(peer, BitcoinMessages.getBlockTxn(
                        new BlockTransactionsRequest(mined.hash(), List.of(0))));
                BitcoinMessage blockTxnWire = take(outbound);
                assertEquals("blocktxn", blockTxnWire.command());
                BlockTransactionsMessage response = BitcoinMessages.decodeBlockTxn(blockTxnWire, 2);
                assertEquals(mined.hash(), response.blockHash());
                assertEquals(List.of(mined.transactions().getFirst()), response.transactions());
            }
        }
    }

    @Test
    void unsupportedSendCmpctVersionIsIgnoredWithoutDisconnectingPeer() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(directory.resolve("unsupported")); var peers = new PeerManager()) {
            var validation = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool());
            var sync = new NodeSyncInfrastructure(db, parameters, () -> 1_800_000_000L);
            var peer = mock(Peer.class);
            when(peer.isReady()).thenReturn(true);
            var incoming = new AtomicReference<PeerMessageListener>();
            doAnswer(i -> {
                incoming.set(i.getArgument(0));
                return null;
            }).when(peer).addMessageListener(any());
            peers.add(peer);
            try (var relay = new NodeRelayService(validation, sync, peers)) {
                incoming.get().onMessage(peer, BitcoinMessages.sendCmpct(new SendCmpctMessage(true, 99)));
                Thread.sleep(100);
                verify(peer, never()).close();
            }
        }
    }

    private static BitcoinMessage take(BlockingQueue<BitcoinMessage> queue) throws InterruptedException {
        BitcoinMessage message = queue.poll(5, TimeUnit.SECONDS);
        assertNotNull(message, "Expected network message");
        return message;
    }
}
