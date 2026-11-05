package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.app.service.NodeRelayService;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.mempool.MempoolEntry;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MempoolPermissionTest {
    @Test void accessRequiresLocalMempoolPermissionOrOfferedBloomService() throws Exception {
        for (var permissions : List.of(Set.<PeerPermission>of(), Set.of(PeerPermission.NOBAN),
                Set.of(PeerPermission.RELAY), Set.of(PeerPermission.FORCERELAY), Set.of(PeerPermission.MEMPOOL))) {
            try (var f = new Fixture(permissions, 0, PeerConnectionRole.FULL_RELAY, true, false)) {
                f.entries(List.of(entry(1, 1000)));
                f.request();
                if (permissions.contains(PeerPermission.MEMPOOL)) assertNotNull(f.inventory.poll(3, TimeUnit.SECONDS));
                else {
                    f.barrier();
                    assertTrue(f.inventory.isEmpty());
                    if (permissions.contains(PeerPermission.NOBAN)) verify(f.peer, never()).close();
                    else verify(f.peer).close();
                }
            }
        }
        try (var f = new Fixture(Set.of(), VersionMessage.NODE_BLOOM, PeerConnectionRole.FULL_RELAY, true, false)) {
            f.entries(List.of(entry(1, 1000)));
            f.request();
            assertNotNull(f.inventory.poll(3, TimeUnit.SECONDS));
        }
    }

    @Test void explicitResponseIgnoresKnownInventoryAndRemoteRelayFalseAndUsesWtxidType() throws Exception {
        try (var f = new Fixture(Set.of(PeerPermission.MEMPOOL, PeerPermission.NOBAN), 0, PeerConnectionRole.INBOUND, false, true)) {
            var entry = entry(1, 1000);
            f.entries(List.of(entry));
            f.listener.get().onMessage(f.peer, BitcoinMessages.inv(new InvMessage(List.of(
                    new InventoryVector(5, entry.transaction().wtxId())))));
            for (int i = 0; i < 2; i++) {
                f.request();
                var result = f.inventory.poll(3, TimeUnit.SECONDS);
                assertNotNull(result);
                assertEquals(List.of(new InventoryVector(5, entry.transaction().wtxId())), BitcoinMessages.decodeInv(result).inventory());
            }
        }
    }

    @Test void responseUsesBaseFeeAndCore32UpwardRounding() throws Exception {
        try (var f = new Fixture(Set.of(PeerPermission.MEMPOOL), 0, PeerConnectionRole.FULL_RELAY, true, false)) {
            var low = entry(1, 100).withFeeDelta(10000);
            var high = entry(2, 101).withFeeDelta(-100);
            f.entries(List.of(low, high));
            f.listener.get().onMessage(f.peer, BitcoinMessages.feeFilter(1001));
            f.request();
            var response = f.inventory.poll(3, TimeUnit.SECONDS);
            assertNotNull(response);
            assertEquals(List.of(new InventoryVector(InventoryVector.MSG_TX, high.transaction().txId())),
                    BitcoinMessages.decodeInv(response).inventory());
        }
    }

    @Test void explicitResponseWorksDuringIbdButBlockRelayRoleCannotQuery() throws Exception {
        try (var f = new Fixture(Set.of(PeerPermission.MEMPOOL), 0, PeerConnectionRole.FULL_RELAY, true, false)) {
            when(f.validation.downloadInitialBlockDownload()).thenReturn(true);
            f.entries(List.of(entry(1, 1000)));
            f.request();
            assertNotNull(f.inventory.poll(3, TimeUnit.SECONDS));
        }
        try (var f = new Fixture(Set.of(PeerPermission.MEMPOOL), 0, PeerConnectionRole.BLOCK_RELAY_ONLY, true, false)) {
            f.entries(List.of(entry(1, 1000)));
            f.request(); f.barrier();
            assertNull(f.inventory.poll(250, TimeUnit.MILLISECONDS));
            verify(f.peer, never()).close();
        }
    }

    @Test void emptyAndFullyFilteredResponsesSendNoEmptyInv() throws Exception {
        try (var f = new Fixture(Set.of(PeerPermission.MEMPOOL, PeerPermission.NOBAN), 0, PeerConnectionRole.FULL_RELAY, true, false)) {
            f.entries(List.of());
            f.request(); f.barrier();
            assertNull(f.inventory.poll(250, TimeUnit.MILLISECONDS));
            f.entries(List.of(entry(1, 0)));
            f.listener.get().onMessage(f.peer, BitcoinMessages.feeFilter(1));
            f.request(); f.barrier();
            assertNull(f.inventory.poll(250, TimeUnit.MILLISECONDS));
        }
    }

    @Test void responseSplitsAtFiftyThousandInventoryVectors() throws Exception {
        try (var f = new Fixture(Set.of(PeerPermission.MEMPOOL), 0, PeerConnectionRole.FULL_RELAY, true, false)) {
            var entries = new ArrayList<MempoolEntry>();
            for (int i = 0; i < 50001; i++) entries.add(entry(i, 1000));
            f.entries(entries);
            f.request();
            var first = f.inventory.poll(10, TimeUnit.SECONDS);
            var second = f.inventory.poll(10, TimeUnit.SECONDS);
            assertNotNull(first); assertNotNull(second);
            var vectors = new ArrayList<>(BitcoinMessages.decodeInv(first).inventory());
            assertEquals(50000, vectors.size());
            assertEquals(1, BitcoinMessages.decodeInv(second).inventory().size());
            vectors.addAll(BitcoinMessages.decodeInv(second).inventory());
            assertEquals(50001, vectors.stream().map(InventoryVector::hash).distinct().count());
        }
    }

    private static MempoolEntry entry(int id, long fee) {
        var tx = new Transaction(2, List.of(new TxIn(new OutPoint(new Hash256(new byte[32]), new UInt32(id)),
                new byte[]{0x51}, TxIn.FINAL_SEQUENCE)), List.of(new TxOut(1000, new byte[]{0x51})), new UInt32(0));
        return new MempoolEntry(tx, fee, 400, 0);
    }

    private static final class Fixture implements AutoCloseable {
        final NodeValidationService validation = mock(NodeValidationService.class);
        final Peer peer = mock(Peer.class);
        final PeerManager peers = new PeerManager();
        final AtomicReference<PeerMessageListener> listener = new AtomicReference<>();
        final LinkedBlockingQueue<BitcoinMessage> inventory = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<BitcoinMessage> headers = new LinkedBlockingQueue<>();
        final NodeRelayService relay;
        Fixture(Set<PeerPermission> permissions, long services, PeerConnectionRole role, boolean wantsRelay, boolean wtxid) throws Exception {
            when(peer.isReady()).thenReturn(true);
            when(peer.localServices()).thenReturn(services);
            when(peer.remoteWtxidRelay()).thenReturn(wtxid);
            for (var permission : PeerPermission.values()) when(peer.hasPermission(permission)).thenReturn(permissions.contains(permission));
            var address = new NetworkAddress(VersionMessage.DEFAULT_SERVICES, new byte[16], 18444);
            when(peer.remoteVersion()).thenReturn(new VersionMessage(70016, VersionMessage.DEFAULT_SERVICES,
                    1800000000L, address, address, 1, "/mempool-test/", 1, wantsRelay));
            doAnswer(call -> { listener.set(call.getArgument(0)); return null; }).when(peer).addMessageListener(any());
            doAnswer(call -> { BitcoinMessage message = call.getArgument(0);
                if (message.command().equals("inv")) inventory.add(message);
                if (message.command().equals("headers")) headers.add(message);
                return null;
            }).when(peer).send(any());
            peers.add(peer, role);
            relay = new NodeRelayService(validation, mock(NodeSyncInfrastructure.class), peers);
        }
        void entries(List<MempoolEntry> entries) { when(validation.mempoolEntries()).thenReturn(entries); }
        void request() { listener.get().onMessage(peer, new BitcoinMessage("mempool", new byte[0])); }
        void barrier() throws Exception {
            var zero = new Hash256(new byte[32]);
            listener.get().onMessage(peer, BitcoinMessages.getHeaders(new GetHeadersMessage(70016, List.of(zero), zero)));
            assertNotNull(headers.poll(3, TimeUnit.SECONDS));
        }
        @Override public void close() throws java.io.IOException { relay.close(); peers.close(); }
    }
}
