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

class OrdinaryRelayFeeFilterTest {
    @Test void baseFeeControlsAnnouncementsWhileMiningOrderIsPreserved() throws Exception {
        var validation = mock(NodeValidationService.class);
        var boosted = entry(1, 100, 10000);
        var lowered = entry(2, 101, -100);
        var exact = entry(3, 100, 0);
        var higher = entry(4, 200, 0);
        // This is an already resolved mining order. The feefilter may remove
        // entries, but must not resort the remaining transactions by base fee.
        var order = List.of(boosted, lowered, higher, exact);
        when(validation.mempoolMiningOrder()).thenReturn(order);
        when(validation.mempoolEntry(any())).thenAnswer(call -> order.stream()
                .filter(entry -> entry.transaction().txId().equals(call.getArgument(0))).findFirst());
        when(validation.admitForBroadcast(any(), anyLong())).thenAnswer(call -> {
            Transaction transaction = call.getArgument(0);
            return new NodeValidationService.BroadcastAdmission(order.stream()
                    .filter(entry -> entry.transaction().txId().equals(transaction.txId())).findFirst().orElseThrow(), false);
        });
        var peer = mock(Peer.class);
        when(peer.isReady()).thenReturn(true);
        when(peer.hasPermission(PeerPermission.NOBAN)).thenReturn(true);
        var address = new NetworkAddress(VersionMessage.DEFAULT_SERVICES, new byte[16], 18444);
        when(peer.remoteVersion()).thenReturn(new VersionMessage(70016, VersionMessage.DEFAULT_SERVICES,
                1800000000L, address, address, 1, "/base-fee-test/", 1, true));
        var listener = new AtomicReference<PeerMessageListener>();
        doAnswer(call -> { listener.set(call.getArgument(0)); return null; }).when(peer).addMessageListener(any());
        var headers = new LinkedBlockingQueue<BitcoinMessage>();
        var inventory = new LinkedBlockingQueue<BitcoinMessage>();
        doAnswer(call -> { BitcoinMessage message = call.getArgument(0);
            if (message.command().equals("headers")) headers.add(message);
            if (message.command().equals("inv")) inventory.add(message);
            return null;
        }).when(peer).send(any());
        try (var peers = new PeerManager()) {
            peers.add(peer, PeerConnectionRole.FULL_RELAY);
            try (var relay = new NodeRelayService(validation, mock(NodeSyncInfrastructure.class), peers)) {
                listener.get().onMessage(peer, BitcoinMessages.feeFilter(1001));
                var zero = new Hash256(new byte[32]);
                listener.get().onMessage(peer, BitcoinMessages.getHeaders(new GetHeadersMessage(70016, List.of(zero), zero)));
                assertNotNull(headers.poll(3, TimeUnit.SECONDS));
                for (var entry : order) relay.submitTransaction(entry.transaction());
                var seen = new ArrayList<Hash256>();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (seen.size() < 2 && System.nanoTime() < deadline) {
                    var message = inventory.poll(100, TimeUnit.MILLISECONDS);
                    if (message != null) BitcoinMessages.decodeInv(message).inventory().forEach(vector -> seen.add(vector.hash()));
                }
                assertEquals(List.of(lowered.transaction().txId(), higher.transaction().txId()), seen);
                assertNull(inventory.poll(250, TimeUnit.MILLISECONDS), "No below-filter transaction may leak later");
                // The exact 100 sat/100 vB transaction is accepted at 1000,
                // proving the fee rounding boundary independently of fee deltas.
                listener.get().onMessage(peer, BitcoinMessages.feeFilter(1000));
                listener.get().onMessage(peer, BitcoinMessages.getHeaders(new GetHeadersMessage(70016, List.of(zero), zero)));
                assertNotNull(headers.poll(3, TimeUnit.SECONDS));
                relay.submitTransaction(exact.transaction());
                var boundary = inventory.poll(3, TimeUnit.SECONDS);
                assertNotNull(boundary);
                assertEquals(List.of(exact.transaction().txId()), BitcoinMessages.decodeInv(boundary).inventory().stream()
                        .map(InventoryVector::hash).toList());
            }
        }
    }

    private static MempoolEntry entry(int id, long fee, long delta) {
        var tx = new Transaction(2, List.of(new TxIn(new OutPoint(new Hash256(new byte[32]), new UInt32(id)),
                new byte[]{0x51}, TxIn.FINAL_SEQUENCE)), List.of(new TxOut(1000, new byte[]{0x51})), new UInt32(0));
        return new MempoolEntry(tx, fee, 400, 0, 0, delta);
    }
}
