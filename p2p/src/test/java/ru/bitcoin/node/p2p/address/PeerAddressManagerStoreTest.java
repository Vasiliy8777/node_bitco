package ru.bitcoin.node.p2p.address;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class PeerAddressManagerStoreTest {
    @TempDir
    Path directory;

    @Test
    void roundTripPreservesSecretMetadataAndTables() throws Exception {
        byte[] secret = new byte[32];
        for (int i = 0; i < secret.length; i++) secret[i] = (byte) (i + 1);
        PeerAddressManager manager = new PeerAddressManager(secret, new java.util.Random(7));
        PeerAddress tried = new PeerAddress(InetAddress.getByName("203.0.113.10"), 8333, 9L);
        PeerAddress fresh = new PeerAddress(InetAddress.getByName("198.51.100.20"), 8333, 1L);
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        manager.add(tried, first);
        manager.markAttempt(tried, first.plusSeconds(10));
        manager.markSuccess(tried, first.plusSeconds(20));
        manager.add(fresh, first.plusSeconds(30));

        PeerAddressManagerStore store = new PeerAddressManagerStore(directory, 0xD9B4BEF9L);
        store.save(manager);
        PeerAddressManager restored = store.load().orElseThrow();

        assertArrayEquals(manager.secretKey(), restored.secretKey());
        assertEquals(manager.size(), restored.size());
        assertEquals(manager.newSize(), restored.newSize());
        assertEquals(manager.triedSize(), restored.triedSize());
        KnownPeerAddress restoredTried = restored.find(tried).orElseThrow();
        assertTrue(restoredTried.isTried());
        assertEquals(first.plusSeconds(20), restoredTried.lastSuccess().orElseThrow());
        assertEquals(0, restoredTried.attempts());
        assertEquals(manager.snapshot().buckets(), restored.snapshot().buckets());
    }

    @Test
    void roundTripAcceptsMoreThanOneHundredThousandKnownAddresses() throws Exception {
        var entries = new java.util.ArrayList<PeerAddressManager.EntrySnapshot>();
        Instant seen = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 100_001; i++) {
            byte[] raw = {11, (byte) (i >>> 16), (byte) (i >>> 8), (byte) i};
            PeerAddress address = new PeerAddress(InetAddress.getByAddress(raw), 8333, 1L);
            entries.add(new PeerAddressManager.EntrySnapshot(address,
                    PeerAddressSource.fromRaw(PeerAddressNetwork.IPV4, raw),
                    seen, seen, null, null, 0, AddrManState.NEW, 0));
        }
        PeerAddressManager manager = PeerAddressManager.restore(
                new PeerAddressManager.Snapshot(new byte[32], entries, java.util.List.of()));
        PeerAddressManagerStore store = new PeerAddressManagerStore(directory, 1L);
        store.save(manager);
        assertEquals(100_001, store.load().orElseThrow().size());
    }

    @Test
    void rejectsEntryCountThatCannotFitInFile() throws Exception {
        PeerAddressManagerStore store = new PeerAddressManagerStore(directory, 1L);
        store.save(new PeerAddressManager());
        try (var file = new java.io.RandomAccessFile(directory.resolve("peers.dat").toFile(), "rw")) {
            file.seek(48);
            file.writeInt(Integer.MAX_VALUE);
        }
        assertThrows(java.io.IOException.class, store::load);
    }

    @Test
    void rejectsSnapshotFromDifferentNetwork() throws Exception {
        PeerAddressManagerStore mainnet = new PeerAddressManagerStore(directory, 1L);
        mainnet.save(new PeerAddressManager());
        PeerAddressManagerStore testnet = new PeerAddressManagerStore(directory, 2L);
        assertThrows(java.io.IOException.class, testnet::load);
    }
}
