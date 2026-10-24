package ru.bitcoin.node.rvn.app.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.rvn.app.chain.*;
import ru.bitcoin.node.rvn.consensus.*;
import ru.bitcoin.node.rvn.protocol.*;
import java.net.*;
import java.nio.file.Path;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.app.chain.RvnMainnetHeaderChainTest.*;

class RvnHeaderSyncServiceTest {
    @TempDir Path temp;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void minedBlockPublisherRequiresPeerPossessionAndFailsWhileOffline(boolean confirms) throws Exception {
        var error = new AtomicReference<Throwable>();
        var ready = new CountDownLatch(1); var received = new CountDownLatch(1);
        var payout = ru.bitcoin.node.rvn.chain.RvnPayoutAddress.parse("RXBurnXXXXXXXXXXXXXXXXXXXXXXWUo9FV", RvnNetwork.MAINNET);
        var block = ru.bitcoin.node.rvn.chain.RvnBlockTemplateBuilder.build(0x30000000,
                RvnMainnetExpectedBits.GENESIS_ID.wire(), 1, 1600000000L, 0x207fffffL, 1, payout, List.of(), 0, true);
        byte[] payload = ru.bitcoin.node.rvn.chain.RvnBlockCodec.serialize(block);
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()); var store = open(temp.resolve("relay"))) {
            var peer = Thread.ofPlatform().daemon(true).start(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var in = socket.getInputStream(); var out = socket.getOutputStream();
                    RvnWire.read(in, RvnNetwork.MAINNET);
                    RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("version", RvnVersion.outbound(70028, server.getLocalPort(), 0)));
                    RvnWire.read(in, RvnNetwork.MAINNET);
                    RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("verack", new byte[0]));
                    assertEquals("getheaders", RvnWire.read(in, RvnNetwork.MAINNET).command());
                    RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("headers", new byte[]{0})); ready.countDown();
                    RvnMessage relayed;
                    while (true) {
                        relayed = RvnWire.read(in, RvnNetwork.MAINNET);
                        if (!relayed.command().equals("getheaders")) break;
                        RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("headers", new byte[]{0}));
                    }
                    assertEquals("block", relayed.command()); assertArrayEquals(payload, relayed.payload());
                    var getdata = RvnWire.read(in, RvnNetwork.MAINNET);
                    assertEquals("getdata", getdata.command());
                    assertArrayEquals(RvnWire.doubleSha(block.header().serialize()), Arrays.copyOfRange(getdata.payload(), 5, 37));
                    RvnWire.write(out, RvnNetwork.MAINNET, confirms ? new RvnMessage("block", payload)
                            : new RvnMessage("notfound", getdata.payload())); received.countDown();
                    while (in.read() != -1) { }
                } catch (Throwable e) { error.set(e); ready.countDown(); received.countDown(); }
            });
            var sync = new RvnHeaderSyncService(new RvnMainnetHeaderChain(store, TEST_POW, () -> 1515000000L),
                    List.of("127.0.0.1"), server.getLocalPort(), 1000, 3000, 100);
            assertThrows(IOException.class, () -> sync.publishMinedBlock(new RvnHash256(RvnWire.doubleSha(block.header().serialize())), block));
            try {
                sync.start(); assertTrue(ready.await(8, TimeUnit.SECONDS));
                if (error.get() != null) fail(error.get());
                if (confirms) sync.publishMinedBlock(new RvnHash256(RvnWire.doubleSha(block.header().serialize())), block);
                else assertThrows(IOException.class, () -> sync.publishMinedBlock(new RvnHash256(RvnWire.doubleSha(block.header().serialize())), block));
                assertTrue(received.await(3, TimeUnit.SECONDS));
            } finally { sync.stop(); }
            peer.join(3000); assertFalse(peer.isAlive()); if (error.get() != null) fail(error.get());
            assertThrows(IOException.class, () -> sync.publishMinedBlock(new RvnHash256(RvnWire.doubleSha(block.header().serialize())), block));
        }
    }

    @Test
    void emptyReplyKeepsCursorOnWeakerPeerBranch() throws Exception {
        var error = new AtomicReference<Throwable>();
        var checked = new CountDownLatch(1);
        byte[] heavy = child(RvnMainnetExpectedBits.GENESIS_ID, 1514999554L);
        ru.bitcoin.node.rvn.pow.RvnKawpowWords.writeLE(0x1d00ffff, heavy, 72);
        var heavyId = new RvnHash256(RvnWire.doubleSha(heavy));
        byte[] first = child(RvnMainnetExpectedBits.GENESIS_ID, 1514999614L);
        byte[] second = child(new RvnHash256(RvnWire.doubleSha(first)), 1514999674L);
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var store = open(temp.resolve("branch"))) {
            // Test-only preloaded branch gives local best greater work than the peer branch.
            store.accept(List.of(heavy), (raw, view) -> new RvnHash256(RvnWire.doubleSha(raw)));
            var peer = Thread.ofPlatform().daemon(true).start(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var in = socket.getInputStream(); var out = socket.getOutputStream();
                    RvnWire.read(in, RvnNetwork.MAINNET);
                    RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("version", RvnVersion.outbound(70028, server.getLocalPort(), 0)));
                    RvnWire.read(in, RvnNetwork.MAINNET);
                    RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("verack", new byte[0]));
                    var request = RvnWire.read(in, RvnNetwork.MAINNET);
                    assertArrayEquals(heavyId.wire(), Arrays.copyOfRange(request.payload(), 5, 37));
                    RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("headers", RvnHeaders.encode(List.of(parsed(first), parsed(second)))));
                    RvnWire.read(in, RvnNetwork.MAINNET);
                    RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("headers", new byte[]{0}));
                    request = RvnWire.read(in, RvnNetwork.MAINNET);
                    assertArrayEquals(RvnWire.doubleSha(second), Arrays.copyOfRange(request.payload(), 5, 37));
                    checked.countDown();
                    while (in.read() != -1) { }
                } catch (Throwable failure) { error.set(failure); checked.countDown(); }
            });
            var chain = new RvnMainnetHeaderChain(store, TEST_POW, () -> 1515000000L);
            var sync = new RvnHeaderSyncService(chain, List.of("127.0.0.1"), server.getLocalPort(), 1000, 3000, 100);
            try {
                sync.start(); assertTrue(checked.await(8, TimeUnit.SECONDS));
                if (error.get() != null) fail(error.get());
                assertEquals(heavyId, chain.best().id());
            } finally { sync.stop(); }
            peer.join(3000); assertFalse(peer.isAlive());
            if (error.get() != null) fail(error.get());
        }
    }

    @Test
    void loopbackSyncPersistsUsesVerifiedLocatorsAndStopsDuringIdleRead() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch advanced = new CountDownLatch(1), disconnected = new CountDownLatch(1);
        byte[] first = child(RvnMainnetExpectedBits.GENESIS_ID, 1514999554L);
        byte[] second = child(new RvnHash256(RvnWire.doubleSha(first)), 1514999614L);
        Path path = temp.resolve("headers");
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()); var store = open(path)) {
            var peer = Thread.ofPlatform().daemon(true).start(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(8000);
                    var in = socket.getInputStream(); var out = socket.getOutputStream();
                    assertEquals("version", RvnWire.read(in, RvnNetwork.MAINNET).command());
                    RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("version", RvnVersion.outbound(70028, server.getLocalPort(), 0)));
                    assertEquals("verack", RvnWire.read(in, RvnNetwork.MAINNET).command());
                    RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("verack", new byte[0]));
                    var request = RvnWire.read(in, RvnNetwork.MAINNET);
                    assertEquals("getheaders", request.command());
                    // protocol(4) + CompactSize(1) precede the first 32-byte locator.
                    assertArrayEquals(RvnMainnetExpectedBits.GENESIS_ID.wire(), Arrays.copyOfRange(request.payload(), 5, 37));
                    byte[] payload = RvnHeaders.encode(List.of(parsed(first), parsed(second)));
                    var frame = new ByteArrayOutputStream();
                    RvnWire.write(frame, RvnNetwork.MAINNET, new RvnMessage("headers", payload));
                    byte[] bytes = frame.toByteArray();
                    out.write(bytes, 0, 3); out.flush();
                    // Exceed the old socket read timeout inside a partially read frame.
                    Thread.sleep(650);
                    out.write(bytes, 3, bytes.length - 3); out.flush();
                    request = RvnWire.read(in, RvnNetwork.MAINNET);
                    assertArrayEquals(RvnWire.doubleSha(second), Arrays.copyOfRange(request.payload(), 5, 37));
                    RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("headers", new byte[]{0}));
                    advanced.countDown();
                    while (in.read() != -1) { }
                    disconnected.countDown();
                } catch (Throwable failure) { error.set(failure); advanced.countDown(); }
            });
            var chain = new RvnMainnetHeaderChain(store, TEST_POW, () -> 1515000000L);
            var sync = new RvnHeaderSyncService(chain, List.of("127.0.0.1"), server.getLocalPort(), 500, 3000, 5000);
            try {
                assertFalse(sync.miningPeerReady());
                sync.start();
                assertTrue(advanced.await(10, TimeUnit.SECONDS));
                if (error.get() != null) fail(error.get());
                assertEquals(2, chain.best().height());
                assertTrue(sync.miningPeerReady());
            } finally { sync.stop(); }
            assertFalse(sync.isRunning());
            assertFalse(sync.miningPeerReady());
            assertTrue(disconnected.await(3, TimeUnit.SECONDS));
            peer.join(3000); assertFalse(peer.isAlive());
            if (error.get() != null) fail(error.get());
        }
        try (var reopened = open(path)) {
            assertEquals(2, reopened.best().height());
            assertArrayEquals(RvnWire.doubleSha(second), reopened.locators().getFirst());
        }
    }
}
