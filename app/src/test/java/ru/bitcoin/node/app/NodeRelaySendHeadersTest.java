package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.service.NodeRelayService;
import ru.bitcoin.node.app.rpc.MiningController;
import ru.bitcoin.node.mempool.FeeRate;
import ru.bitcoin.node.chain.BlockProcessingResult;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.p2p.PeerMessageListener;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NodeRelaySendHeadersTest {

    @TempDir
    Path directory;

    @Test
    void sendHeadersPreferenceUsesHeadersWhileLegacyPeerGetsInvAndSourceIsExcluded() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();

        try (var db = new RocksDbDatabase(directory);
             var peers = new PeerManager()) {

            var validation = new NodeValidationService(
                    db,
                    parameters,
                    () -> 1_800_000_000L,
                    new Mempool()
            );
            var sync = new NodeSyncInfrastructure(
                    db,
                    parameters,
                    () -> 1_800_000_000L
            );

            Peer source = mock(Peer.class);
            Peer headersPeer = mock(Peer.class);
            Peer invPeer = mock(Peer.class);
            when(source.isReady()).thenReturn(true);
            when(headersPeer.isReady()).thenReturn(true);
            when(invPeer.isReady()).thenReturn(true);

            var sourceIncoming = new AtomicReference<PeerMessageListener>();
            var headersIncoming = new AtomicReference<PeerMessageListener>();
            var invIncoming = new AtomicReference<PeerMessageListener>();
            captureListener(source, sourceIncoming);
            captureListener(headersPeer, headersIncoming);
            captureListener(invPeer, invIncoming);

            BlockingQueue<BitcoinMessage> sourceOutbound = new LinkedBlockingQueue<>();
            BlockingQueue<BitcoinMessage> headersOutbound = new LinkedBlockingQueue<>();
            BlockingQueue<BitcoinMessage> invOutbound = new LinkedBlockingQueue<>();
            captureOutbound(source, sourceOutbound);
            captureOutbound(headersPeer, headersOutbound);
            captureOutbound(invPeer, invOutbound);

            peers.add(source);
            peers.add(headersPeer);
            peers.add(invPeer);

            try (var relay = new NodeRelayService(validation, sync, peers)) {
                headersIncoming.get().onMessage(headersPeer, BitcoinMessages.sendHeaders());

                // Barrier on the relay's single inbound worker: once this GETHEADERS
                // response arrives, the preceding SENDHEADERS preference was processed.
                headersIncoming.get().onMessage(
                        headersPeer,
                        BitcoinMessages.getHeaders(new GetHeadersMessage(
                                VersionMessage.CURRENT_PROTOCOL_VERSION,
                                List.of(parameters.genesisBlockHash()),
                                new Hash256(new byte[32])
                        ))
                );
                assertEquals("headers", take(headersOutbound).command());

                var mining = new MiningController(validation, relay, parameters, () -> true, new byte[]{0x51}, 4_000_000, new FeeRate(0));
                var template = mining.getBlockTemplate(java.util.Map.of("rules", List.of("segwit")));
                var block = MiningRpcTest.mineTemplate(template);
                assertEquals(BlockProcessingResult.CONNECTED, validation.processBlock(block));
                relay.relayConnectedBlock(block, source);

                BitcoinMessage headerAnnouncement = take(headersOutbound);
                assertEquals("headers", headerAnnouncement.command());
                var announcedHeaders = BitcoinMessages.decodeHeaders(headerAnnouncement).headers();
                assertEquals(1, announcedHeaders.size());
                assertEquals(block.hash(), announcedHeaders.getFirst().hash());

                BitcoinMessage invAnnouncement = take(invOutbound);
                assertEquals("inv", invAnnouncement.command());
                var inventory = BitcoinMessages.decodeInv(invAnnouncement).inventory();
                assertEquals(1, inventory.size());
                assertEquals(InventoryVector.MSG_BLOCK, inventory.getFirst().type());
                assertEquals(block.hash(), inventory.getFirst().hash());

                assertNull(sourceOutbound.poll(250, TimeUnit.MILLISECONDS),
                        "Block must not be announced back to its source peer");
            }
        }
    }

    @Test
    void repeatedSendHeadersIsIdempotent() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();

        try (var db = new RocksDbDatabase(directory.resolve("repeat"));
             var peers = new PeerManager()) {

            var validation = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool());
            var sync = new NodeSyncInfrastructure(db, parameters, () -> 1_800_000_000L);
            Peer peer = mock(Peer.class);
            when(peer.isReady()).thenReturn(true);

            var incoming = new AtomicReference<PeerMessageListener>();
            captureListener(peer, incoming);
            BlockingQueue<BitcoinMessage> outbound = new LinkedBlockingQueue<>();
            captureOutbound(peer, outbound);
            peers.add(peer);

            try (var relay = new NodeRelayService(validation, sync, peers)) {
                incoming.get().onMessage(peer, BitcoinMessages.sendHeaders());
                incoming.get().onMessage(peer, BitcoinMessages.sendHeaders());
                incoming.get().onMessage(peer, BitcoinMessages.getHeaders(new GetHeadersMessage(
                        VersionMessage.CURRENT_PROTOCOL_VERSION,
                        List.of(parameters.genesisBlockHash()),
                        new Hash256(new byte[32])
                )));
                assertEquals("headers", take(outbound).command());

                var mining = new MiningController(validation, relay, parameters, () -> true, new byte[]{0x51}, 4_000_000, new FeeRate(0));
                var template = mining.getBlockTemplate(java.util.Map.of("rules", List.of("segwit")));
                var block = MiningRpcTest.mineTemplate(template);
                assertEquals(BlockProcessingResult.CONNECTED, validation.processBlock(block));
                relay.relayConnectedBlock(block, null);
                BitcoinMessage announcement = take(outbound);
                assertEquals("headers", announcement.command());
                assertEquals(block.hash(), BitcoinMessages.decodeHeaders(announcement).headers().getFirst().hash());
                assertNull(outbound.poll(250, TimeUnit.MILLISECONDS));
            }
        }
    }

    @Test
    void sendHeadersFallsBackToInvWhenNoConnectingHeaderIsKnown() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(directory.resolve("fallback")); var peers = new PeerManager()) {
            var validation = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool());
            var sync = new NodeSyncInfrastructure(db, parameters, () -> 1_800_000_000L);
            Peer peer = mock(Peer.class); when(peer.isReady()).thenReturn(true);
            var incoming = new AtomicReference<PeerMessageListener>(); captureListener(peer, incoming);
            BlockingQueue<BitcoinMessage> outbound = new LinkedBlockingQueue<>(); captureOutbound(peer, outbound);
            peers.add(peer);
            try (var relay = new NodeRelayService(validation, sync, peers)) {
                incoming.get().onMessage(peer, BitcoinMessages.sendHeaders());
                incoming.get().onMessage(peer, BitcoinMessages.getHeaders(new GetHeadersMessage(
                        VersionMessage.CURRENT_PROTOCOL_VERSION,
                        List.of(Hash256.fromDisplayHex("55".repeat(32))), new Hash256(new byte[32]))));
                assertEquals("headers", take(outbound).command());
                var mining = new MiningController(validation, relay, parameters, () -> true, new byte[]{0x51}, 4_000_000, new FeeRate(0));
                var template = mining.getBlockTemplate(java.util.Map.of("rules", List.of("segwit")));
                var block = MiningRpcTest.mineTemplate(template);
                assertEquals(BlockProcessingResult.CONNECTED, validation.processBlock(block));
                relay.relayConnectedBlock(block, null);
                BitcoinMessage announcement = take(outbound);
                assertEquals("inv", announcement.command());
                assertEquals(block.hash(), BitcoinMessages.decodeInv(announcement).inventory().getFirst().hash());
            }
        }
    }

    private static void captureListener(Peer peer, AtomicReference<PeerMessageListener> target) {
        doAnswer(invocation -> {
            target.set(invocation.getArgument(0));
            return null;
        }).when(peer).addMessageListener(any());
    }

    private static void captureOutbound(Peer peer, BlockingQueue<BitcoinMessage> target) throws Exception {
        doAnswer(invocation -> {
            target.add(invocation.getArgument(0));
            return null;
        }).when(peer).send(any());
    }

    private static BitcoinMessage take(BlockingQueue<BitcoinMessage> queue) throws InterruptedException {
        BitcoinMessage message = queue.poll(5, TimeUnit.SECONDS);
        assertNotNull(message, "Expected network message");
        return message;
    }
}
