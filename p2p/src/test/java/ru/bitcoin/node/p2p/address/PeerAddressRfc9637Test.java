package ru.bitcoin.node.p2p.address;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.message.AddrV2Entry;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PeerAddressRfc9637Test {
    @Test void rejectsEntireDocumentationPrefixWithoutRejectingAdjacentNetworks() throws Exception {
        for (String host : List.of("3fff::", "3fff::1", "3fff:fff:ffff:ffff:ffff:ffff:ffff:ffff")) {
            var address = address(host);
            assertTrue(address.isRfc9637Documentation(), host);
            assertNull(PeerAddressProtocol.toPeerAddress(new AddrV2Entry(
                    1, 9, 2, address.rawAddress(), 8333)), host);
            var manager = new PeerAddressManager();
            manager.add(address, Instant.now());
            assertTrue(manager.isEmpty(), host);
            assertTrue(manager.select().isEmpty(), host);
            assertTrue(manager.getAddr().isEmpty(), host);
        }
        for (String host : List.of("3ffe:ffff::1", "3fff:1000::1", "4000::1")) {
            var address = address(host);
            assertFalse(address.isRfc9637Documentation(), host);
            assertNotNull(PeerAddressProtocol.toPeerAddress(new AddrV2Entry(
                    1, 9, 2, address.rawAddress(), 8333)), host);
        }
    }

    @Test void removesOldDocumentationEntriesAndBucketReferencesOnRestore() throws Exception {
        var manager = new PeerAddressManager();
        manager.add(address("2606:4700::1111"), Instant.now());
        var valid = manager.snapshot();
        var entry = valid.entries().getFirst();
        var invalid = address("3fff::1");
        var oldEntry = new PeerAddressManager.EntrySnapshot(invalid, entry.source(),
                entry.firstSeen(), entry.lastSeen(), entry.lastAttempt(), entry.lastSuccess(),
                entry.attempts(), entry.state(), entry.newBucketReferences());
        var buckets = valid.buckets().stream().map(bucket -> new PeerAddressManager.BucketSnapshot(
                bucket.tried(), bucket.bucket(), bucket.slot(), invalid)).toList();
        var restored = PeerAddressManager.restore(new PeerAddressManager.Snapshot(
                valid.secretKey(), List.of(oldEntry), buckets));
        assertTrue(restored.isEmpty());
        assertTrue(restored.snapshot().buckets().isEmpty());
    }

    private static PeerAddress address(String host) throws Exception {
        return new PeerAddress(InetAddress.getByName(host), 8333, 9);
    }
}
