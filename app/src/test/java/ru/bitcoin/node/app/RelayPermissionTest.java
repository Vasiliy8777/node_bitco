package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.app.service.NodeRelayService;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.mempool.MempoolEntry;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RelayPermissionTest {
    private static Transaction transaction() {
        return new Transaction(2, List.of(new TxIn(new OutPoint(Hash256.fromDisplayHex("12".repeat(32)),
                new UInt32(0)), new byte[]{0x51}, TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(1000, new byte[]{0x51})), new UInt32(0));
    }

    @Test void newUnsolicitedTransactionAlwaysGoesThroughAdmission() throws Exception {
        for (var permission : List.of(Set.<PeerPermission>of(), Set.of(PeerPermission.RELAY), Set.of(PeerPermission.FORCERELAY))) {
            try (var fixture = new Fixture(permission, false, false)) {
                fixture.receiveTx();
                verify(fixture.validation, timeout(3000)).admit(argThat(tx -> tx.txId().equals(fixture.tx.txId())));
                verify(fixture.source, timeout(3000)).recordAcceptedTransaction();
            }
        }
    }

    @Test void forceRelayCannotBypassInvalidTransactionAdmission() throws Exception {
        try (var fixture = new Fixture(Set.of(PeerPermission.FORCERELAY), false, false)) {
            doThrow(new ru.bitcoin.node.consensus.transaction.TransactionValidationException("invalid-test-tx"))
                    .when(fixture.validation).admit(any());
            fixture.receiveTx();
            fixture.barrier();
            verify(fixture.validation).admit(any());
            verify(fixture.source, never()).recordAcceptedTransaction();
            assertEquals(0, ((Map<?, ?>) fixture.relay.inventoryBucketInfo().get("outbound")).get("backlog"));
        }
    }

    @Test void onlyForceRelayReannouncesValidatedDuplicateAndManualPeersReceiveIt() throws Exception {
        for (boolean force : List.of(false, true)) {
            try (var fixture = new Fixture(force ? Set.of(PeerPermission.FORCERELAY) : Set.of(PeerPermission.RELAY), false, true)) {
                var destination = fixture.peer(Set.of());
                when(destination.isReady()).thenReturn(true);
                var announcements = new LinkedBlockingQueue<BitcoinMessage>();
                doAnswer(call -> { BitcoinMessage message = call.getArgument(0);
                    if (message.command().equals("inv")) announcements.add(message); return null;
                }).when(destination).send(any());
                fixture.peers.add(destination, PeerConnectionRole.MANUAL);
                fixture.receiveTx();
                fixture.barrier();
                if (force) {
                    var message = announcements.poll(30, TimeUnit.SECONDS);
                    assertNotNull(message, "ForceRelay must announce the existing mempool transaction to a manual peer");
                    assertEquals(fixture.tx.txId(), BitcoinMessages.decodeInv(message).inventory().getFirst().hash());
                } else assertNull(announcements.poll(300, TimeUnit.MILLISECONDS));
                verify(fixture.validation, never()).admit(any());
                verify(fixture.source, never()).recordAcceptedTransaction();
            }
        }
    }

    @Test void blocksOnlyRequiresRelayAndDoesNotTreatNoBanAsRelay() throws Exception {
        for (var permission : List.of(Set.<PeerPermission>of(), Set.of(PeerPermission.NOBAN),
                Set.of(PeerPermission.RELAY), Set.of(PeerPermission.FORCERELAY))) {
            try (var fixture = new Fixture(permission, true, false)) {
                fixture.receiveTx();
                fixture.barrier();
                boolean allowed = permission.contains(PeerPermission.RELAY) || permission.contains(PeerPermission.FORCERELAY);
                if (allowed) {
                    verify(fixture.validation).admit(any());
                    verify(fixture.source, never()).close();
                } else {
                    verify(fixture.validation, never()).admit(any());
                    verify(fixture.source).close();
                }
            }
        }
    }

    @Test void blocksOnlyRejectsTxInventoryEvenDuringIbdButRelayDoesNotBypassIbd() throws Exception {
        for (boolean allowed : List.of(false, true)) {
            try (var fixture = new Fixture(allowed ? Set.of(PeerPermission.RELAY) : Set.of(), true, false)) {
                when(fixture.validation.downloadInitialBlockDownload()).thenReturn(true);
                fixture.listener.get().onMessage(fixture.source, BitcoinMessages.inv(new InvMessage(List.of(
                        new InventoryVector(InventoryVector.MSG_TX, fixture.tx.txId())))));
                fixture.barrier();
                if (!allowed) verify(fixture.source).close();
                else verify(fixture.source, never()).close();
                verify(fixture.validation, never()).admit(any());
            }
        }
    }

    @Test void forceRelayPeersDoNotReceiveFeeFilters() throws Exception {
        try (var fixture = new Fixture(Set.of(PeerPermission.FORCERELAY), false, false)) {
            when(fixture.source.isReady()).thenReturn(true);
            when(fixture.validation.feeFilterRate()).thenReturn(1000L);
            when(fixture.validation.minimumRelayFeeRate()).thenReturn(1000L);
            // A normal peer proves that the scheduled fee-filter pass actually ran.
            var ordinary = fixture.peer(Set.of());
            when(ordinary.isReady()).thenReturn(true);
            fixture.peers.add(ordinary, PeerConnectionRole.FULL_RELAY);
            verify(ordinary, timeout(3000)).send(argThat(message -> message.command().equals("feefilter")));
            verify(fixture.source, never()).send(argThat(message -> message.command().equals("feefilter")));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Transaction tx = transaction();
        final NodeValidationService validation = mock(NodeValidationService.class);
        final PeerManager peers = new PeerManager();
        final AtomicReference<PeerMessageListener> listener = new AtomicReference<>();
        final Peer source;
        final NodeRelayService relay;
        final LinkedBlockingQueue<BitcoinMessage> responses = new LinkedBlockingQueue<>();
        Fixture(Set<PeerPermission> permissions, boolean blocksOnly, boolean known) throws Exception {
            var entry = new MempoolEntry(tx, 1000, 400, 0);
            when(validation.mempoolEntries()).thenReturn(known ? List.of(entry) : List.of());
            when(validation.mempoolEntry(tx.txId())).thenReturn(known ? Optional.of(entry) : Optional.empty());
            when(validation.mempoolMiningOrder()).thenReturn(known ? List.of(entry) : List.of());
            when(validation.admit(any())).thenReturn(entry);
            source = peer(permissions);
            when(source.isReady()).thenReturn(true);
            doAnswer(call -> { listener.set(call.getArgument(0)); return null; }).when(source).addMessageListener(any());
            doAnswer(call -> { BitcoinMessage message = call.getArgument(0);
                if (message.command().equals("headers")) responses.add(message); return null;
            }).when(source).send(any());
            peers.add(source, PeerConnectionRole.FULL_RELAY);
            relay = new NodeRelayService(validation, mock(NodeSyncInfrastructure.class), peers, null, 14, blocksOnly);
        }
        Peer peer(Set<PeerPermission> permissions) {
            var peer = mock(Peer.class);
            var effective = new PeerAccess(permissions, false).permissions();
            for (var permission : PeerPermission.values()) when(peer.hasPermission(permission)).thenReturn(effective.contains(permission));
            var address = new NetworkAddress(VersionMessage.DEFAULT_SERVICES, new byte[16], 18444);
            when(peer.remoteVersion()).thenReturn(new VersionMessage(VersionMessage.CURRENT_PROTOCOL_VERSION,
                    VersionMessage.DEFAULT_SERVICES, 1_800_000_000L, address, address, 1, "/permission-test/", 1, true));
            return peer;
        }
        void receiveTx() { listener.get().onMessage(source, new BitcoinMessage("tx", TransactionSerializer.serialize(tx))); }
        void barrier() throws Exception {
            var zero = new Hash256(new byte[32]);
            listener.get().onMessage(source, BitcoinMessages.getHeaders(new GetHeadersMessage(70016, List.of(zero), zero)));
            assertNotNull(responses.poll(3, TimeUnit.SECONDS), "Relay worker did not reach the response barrier");
        }
        @Override public void close() throws java.io.IOException { relay.close(); peers.close(); }
    }
}
