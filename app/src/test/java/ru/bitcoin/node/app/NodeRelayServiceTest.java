package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.service.NodeRelayService;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.crypto.hash.Hash160;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.utxo.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeRelayServiceTest {
    @TempDir Path directory;

    @Test void requestsMissingParentRetriesChildAndServesAdmittedTransaction() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        byte[] script = HexFormat.of().parseHex("a914" + HexFormat.of().formatHex(Hash160.hash(new byte[]{0x51})) + "87");
        try (var db = new RocksDbDatabase(directory); var peers = new PeerManager()) {
            var validation = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool());
            var sync = new NodeSyncInfrastructure(db, parameters, () -> 1_800_000_000L);
            var fund = new OutPoint(Hash256.fromDisplayHex("11".repeat(32)), new UInt32(0));
            new RocksDbUtxoStore(db).save(fund, new StoredUtxo(100_000, script, 0, false));
            var parent = spend(fund, 90_000, script);
            var child = spend(new OutPoint(parent.txId(), new UInt32(0)), 80_000, script);
            var source = mock(Peer.class);
            var destination = mock(Peer.class);
            var version = mock(VersionMessage.class);
            when(version.relay()).thenReturn(true);
            when(source.isReady()).thenReturn(true);
            when(destination.isReady()).thenReturn(true);
            when(source.remoteVersion()).thenReturn(version);
            when(destination.remoteVersion()).thenReturn(version);
            when(destination.remoteWtxidRelay()).thenReturn(true);
            var incoming = new AtomicReference<PeerMessageListener>();
            doAnswer(invocation -> { incoming.set(invocation.getArgument(0)); return null; }).when(source).addMessageListener(any());
            var requests = new LinkedBlockingQueue<BitcoinMessage>();
            var announcements = new LinkedBlockingQueue<BitcoinMessage>();
            doAnswer(invocation -> { requests.add(invocation.getArgument(0)); return null; }).when(source).send(any());
            doAnswer(invocation -> { announcements.add(invocation.getArgument(0)); return null; }).when(destination).send(any());
            peers.add(source);
            try (var relay = new NodeRelayService(validation, sync, peers)) {
                peers.add(destination); // New connections must be observed too.
                incoming.get().onMessage(source, BitcoinMessages.inv(new InvMessage(List.of(new InventoryVector(5, child.wtxId())))));
                assertEquals(child.wtxId(), BitcoinMessages.decodeGetData(take(requests)).inventory().getFirst().hash());
                incoming.get().onMessage(source, new BitcoinMessage("tx", TransactionSerializer.serialize(child)));
                assertEquals(parent.txId(), BitcoinMessages.decodeGetData(take(requests)).inventory().getFirst().hash());
                assertTrue(validation.mempoolEntries().isEmpty());
                incoming.get().onMessage(source, new BitcoinMessage("tx", TransactionSerializer.serialize(parent)));
                assertEquals(parent.wtxId(), BitcoinMessages.decodeInv(take(announcements)).inventory().getFirst().hash());
                assertEquals(child.wtxId(), BitcoinMessages.decodeInv(take(announcements)).inventory().getFirst().hash());
                assertEquals(2, validation.mempoolEntries().size());
                assertEquals(child.wtxId(), BitcoinMessages.decodeInv(take(requests)).inventory().getFirst().hash());
                incoming.get().onMessage(source, BitcoinMessages.getData(new GetDataMessage(List.of(new InventoryVector(5, child.wtxId())))));
                var served = take(requests);
                assertEquals("tx", served.command());
                assertArrayEquals(TransactionSerializer.serialize(child), served.payload());
                var unknown = new InventoryVector(InventoryVector.MSG_WITNESS_BLOCK, new Hash256(new byte[32]));
                incoming.get().onMessage(source, BitcoinMessages.getData(new GetDataMessage(List.of(unknown))));
                assertEquals(List.of(unknown), BitcoinMessages.decodeNotFound(take(requests)).inventory());
            }
            verify(source).removeMessageListener(incoming.get());
        }
    }

    private static BitcoinMessage take(BlockingQueue<BitcoinMessage> queue) throws InterruptedException {
        var message = queue.poll(5, TimeUnit.SECONDS);
        assertNotNull(message, "Expected network message");
        return message;
    }
    private static Transaction spend(OutPoint point, long value, byte[] script) {
        return new Transaction(2, List.of(new TxIn(point, new byte[]{1,0x51}, TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(value, script)), new UInt32(0));
    }
}
