package ru.bitcoin.node.app.service;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.protocol.transaction.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class TxOrphanageTest {

    @Test
    void indexesChildrenByParentWithoutScanningAllOrphans() {
        var orphanage = new TxOrphanage();
        Peer peer = mock(Peer.class);
        Transaction parent = tx(hash(1), 0, 90_000);
        Transaction child = tx(parent.txId(), 0, 80_000);
        Transaction unrelated = tx(hash(2), 0, 70_000);

        assertTrue(orphanage.add(child, peer));
        assertTrue(orphanage.add(unrelated, peer));
        assertEquals(List.of(child), orphanage.childrenOf(parent.txId()));
        assertTrue(orphanage.childrenOf(hash(99)).isEmpty());

        assertTrue(orphanage.remove(child));
        assertTrue(orphanage.childrenOf(parent.txId()).isEmpty());
    }

    @Test
    void deduplicatesSameWitnessTransactionAndTracksAnnouncers() {
        var orphanage = new TxOrphanage();
        Peer first = mock(Peer.class);
        Peer second = mock(Peer.class);
        Transaction orphan = tx(hash(3), 0, 80_000);

        assertTrue(orphanage.add(orphan, first));
        assertFalse(orphanage.add(orphan, second));
        assertEquals(1, orphanage.size());

        orphanage.removePeer(first);
        assertEquals(1, orphanage.size(), "second announcer keeps the orphan alive");
        orphanage.removePeer(second);
        assertEquals(0, orphanage.size());
    }

    @Test
    void resourcePressureEvictsFromResourceIntensivePeer() {
        Peer noisy = mock(Peer.class);
        Peer useful = mock(Peer.class);
        Transaction usefulTx = tx(hash(10), 0, 50_000);
        long oneWeight = ru.bitcoin.node.consensus.transaction.TransactionWeight.calculate(usefulTx);
        var orphanage = new TxOrphanage(oneWeight * 2, 100);

        assertTrue(orphanage.add(usefulTx, useful));
        Transaction noisy1 = tx(hash(11), 0, 40_000);
        Transaction noisy2 = tx(hash(12), 0, 30_000);
        Transaction noisy3 = tx(hash(13), 0, 20_000);
        orphanage.add(noisy1, noisy);
        orphanage.add(noisy2, noisy);
        orphanage.add(noisy3, noisy);

        assertTrue(orphanage.size() <= 4);
        assertTrue(orphanage.totalWeight() <= oneWeight * 4);
        assertEquals(List.of(usefulTx), orphanage.childrenOf(hash(10)));
    }

    @Test
    void connectedTransactionRemovesOrphansSpendingTheSamePrevout() {
        var orphanage = new TxOrphanage();
        Peer peer = mock(Peer.class);
        Hash256 funding = hash(30);
        Transaction orphan = tx(funding, 0, 70_000);
        Transaction confirmedConflict = tx(funding, 0, 60_000);
        assertTrue(orphanage.add(orphan, peer));

        orphanage.removeConflicts(confirmedConflict);

        assertEquals(0, orphanage.size());
        assertTrue(orphanage.childrenOf(funding).isEmpty());
    }

    @Test
    void removingEntryCleansEveryParentIndex() {
        var orphanage = new TxOrphanage();
        Peer peer = mock(Peer.class);
        Hash256 firstParent = hash(21);
        Hash256 secondParent = hash(22);
        Transaction orphan = new Transaction(2,
                List.of(input(firstParent, 0), input(secondParent, 1)),
                List.of(new TxOut(10_000, new byte[]{0x51})), new UInt32(0));

        assertTrue(orphanage.add(orphan, peer));
        assertEquals(List.of(orphan), orphanage.childrenOf(firstParent));
        assertEquals(List.of(orphan), orphanage.childrenOf(secondParent));
        orphanage.remove(orphan);
        assertTrue(orphanage.childrenOf(firstParent).isEmpty());
        assertTrue(orphanage.childrenOf(secondParent).isEmpty());
    }

    private static Transaction tx(Hash256 parent, long index, long value) {
        return new Transaction(2, List.of(input(parent, index)),
                List.of(new TxOut(value, new byte[]{0x51})), new UInt32(0));
    }

    private static TxIn input(Hash256 parent, long index) {
        return new TxIn(new OutPoint(parent, new UInt32(index)), new byte[0], TxIn.FINAL_SEQUENCE);
    }

    private static Hash256 hash(int marker) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) marker;
        return new Hash256(bytes);
    }
}
