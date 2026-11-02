package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.p2p.address.PeerAddress;
import java.nio.file.*;
import java.net.InetAddress;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BlockRelayAnchorStoreTest {
    @TempDir Path directory;
    @Test void roundTripsTwoEndpointsServicesAndConsumesOnlyOnce() throws Exception {
        var store = new BlockRelayAnchorStore(directory, 0xD9B4BEF9L);
        var a = new PeerAddress(InetAddress.getByName("8.8.8.8"), 8333, 0x8000000000000009L);
        var b = new PeerAddress(InetAddress.getByName("2001:4860::8888"), 18333, 1033);
        store.save(List.of(a, a, b, new PeerAddress(InetAddress.getByName("9.9.9.9"), 8333, 9)));
        var loaded = store.consume();
        assertEquals(List.of(a, b), loaded);
        assertEquals(a.services(), loaded.getFirst().services());
        assertEquals(b.services(), loaded.getLast().services());
        assertTrue(store.consume().isEmpty());
        assertFalse(Files.exists(directory.resolve("block-relay-anchors.dat")));
    }
    @Test void rejectsWrongNetworkAndConsumesTheFile() throws Exception {
        new BlockRelayAnchorStore(directory, 1).save(List.of());
        assertThrows(java.io.IOException.class, () -> new BlockRelayAnchorStore(directory, 2).consume());
        assertTrue(new BlockRelayAnchorStore(directory, 1).consume().isEmpty());
    }
    @Test void rejectsCorruptOrOversizedFilesWithoutRetainingThem() throws Exception {
        var store = new BlockRelayAnchorStore(directory, 1);
        store.save(List.of(new PeerAddress(InetAddress.getByName("8.8.8.8"), 8333, 9)));
        Path path = directory.resolve("block-relay-anchors.dat");
        byte[] data = Files.readAllBytes(path); data[8] ^= 1; Files.write(path, data);
        assertThrows(java.io.IOException.class, store::consume);
        Files.write(path, new byte[1025]);
        assertThrows(java.io.IOException.class, store::consume);
        assertTrue(store.consume().isEmpty());
    }
}
