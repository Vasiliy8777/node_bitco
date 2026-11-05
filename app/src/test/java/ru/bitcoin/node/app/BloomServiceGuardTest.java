package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.app.service.NodeRelayService;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.message.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BloomServiceGuardTest {
    @Test void filteredRequestClosesBeforeAnyBlockLookupAndStopsTheBatch() throws Exception {
        try (var fixture = new Fixture()) {
            for (Hash256 hash : List.of(new Hash256(new byte[32]), Hash256.fromDisplayHex("31".repeat(32)))) {
                var endpoint = new Endpoint(fixture.peers);
                endpoint.message(BitcoinMessages.getData(new GetDataMessage(List.of(
                        new InventoryVector(InventoryVector.MSG_FILTERED_BLOCK, hash),
                        new InventoryVector(InventoryVector.MSG_WITNESS_BLOCK, hash), endpoint.missingTx))));
                endpoint.assertClosedWithoutPenalty();
            }
            verify(fixture.validation, never()).requestedBlock(any(), anyLong());
            verify(fixture.validation, never()).requestedBlockTransactions(any(), anyLong());
        }
    }

    @Test void unsupportedBloomControlsCloseBeforeParsingTheirPayloads() throws Exception {
        try (var fixture = new Fixture()) {
            assertEquals(0, VersionMessage.DEFAULT_SERVICES & VersionMessage.NODE_BLOOM);
            for (String command : List.of("filterload", "filteradd", "filterclear")) {
                for (byte[] payload : List.of(new byte[0], new byte[]{(byte) 0xfd, (byte) 0xff, 1})) {
                    var endpoint = new Endpoint(fixture.peers);
                    endpoint.message(new BitcoinMessage(command, payload));
                    endpoint.assertClosedWithoutPenalty();
                }
            }
            verify(fixture.validation, never()).requestedBlock(any(), anyLong());
        }
    }

    @Test void reservedWitnessFilteredTypeAndUnknownCommandsRemainIgnored() throws Exception {
        try (var fixture = new Fixture()) {
            var endpoint = new Endpoint(fixture.peers);
            endpoint.message(new BitcoinMessage("unknowncmd", new byte[0]));
            endpoint.message(BitcoinMessages.getData(new GetDataMessage(List.of(new InventoryVector(
                    InventoryVector.MSG_FILTERED_BLOCK | InventoryVector.MSG_WITNESS_FLAG,
                    new Hash256(new byte[32])), endpoint.missingTx))));
            var reply = endpoint.outgoing.poll(3, TimeUnit.SECONDS); assertNotNull(reply);
            assertEquals(List.of(endpoint.missingTx), BitcoinMessages.decodeNotFound(reply).inventory());
            verify(endpoint.peer, never()).close();
            verify(endpoint.peer, never()).disconnectForProtocolViolation(anyString(), any());
            verify(fixture.validation, never()).requestedBlock(any(), anyLong());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final NodeValidationService validation = mock(NodeValidationService.class);
        final PeerManager peers = new PeerManager();
        final NodeRelayService relay = new NodeRelayService(validation, mock(NodeSyncInfrastructure.class), peers);
        public void close() throws Exception { relay.close(); peers.close(); }
    }

    private static final class Endpoint {
        final Peer peer = mock(Peer.class);
        final AtomicReference<PeerMessageListener> incoming = new AtomicReference<>();
        final AtomicBoolean ready = new AtomicBoolean(true);
        final LinkedBlockingQueue<BitcoinMessage> outgoing = new LinkedBlockingQueue<>();
        final InventoryVector missingTx = new InventoryVector(InventoryVector.MSG_TX, Hash256.fromDisplayHex("47".repeat(32)));
        Endpoint(PeerManager peers) throws Exception {
            when(peer.isReady()).thenAnswer(call -> ready.get());
            // Compact filters (BIP157) and the remote's Bloom capability do not
            // grant BIP37 service on this local connection.
            when(peer.localServices()).thenReturn(VersionMessage.DEFAULT_SERVICES | VersionMessage.NODE_COMPACT_FILTERS);
            when(peer.remoteVersion()).thenReturn(new VersionMessage(VersionMessage.CURRENT_PROTOCOL_VERSION,
                    VersionMessage.DEFAULT_SERVICES | VersionMessage.NODE_BLOOM, 0,
                    NetworkAddress.unspecified(), NetworkAddress.unspecified(), 1, "/bloom-guard-test/", 0, true));
            doAnswer(call -> { incoming.set(call.getArgument(0)); return null; }).when(peer).addMessageListener(any());
            doAnswer(call -> { ready.set(false); return null; }).when(peer).close();
            doAnswer(call -> {
                BitcoinMessage message = call.getArgument(0);
                if (!"feefilter".equals(message.command())) outgoing.add(message);
                return null;
            }).when(peer).send(any());
            peers.add(peer);
        }
        void message(BitcoinMessage message) throws Exception { incoming.get().onMessage(peer, message); }
        void assertClosedWithoutPenalty() throws Exception {
            verify(peer, timeout(3000)).close();
            verify(peer, never()).disconnectForProtocolViolation(anyString(), any());
            assertTrue(outgoing.isEmpty());
        }
    }
}
