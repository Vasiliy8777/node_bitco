package ru.bitcoin.node.rvn.app.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.rvn.app.chain.RvnMainnetHeaderChain;
import ru.bitcoin.node.rvn.chain.*;
import ru.bitcoin.node.rvn.consensus.*;
import ru.bitcoin.node.rvn.protocol.*;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.app.chain.RvnMainnetHeaderChainTest.*;

class RvnBlockDownloadIntegrationTest {
    @TempDir Path temp;
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void headersFirstRequiresEndOfHeadersAndRejectsStalePeerTips(boolean recent) throws Exception {
        long now = System.currentTimeMillis() / 1000;
        var parent = RvnMainnetExpectedBits.GENESIS_ID; var blocks = new ArrayList<RvnBlock>();
        for (int height = 1; height <= 2; height++) {
            var original = block(parent, height); byte[] raw = original.header().serialize();
            if (recent) {
                raw = Arrays.copyOf(raw, 120);
                ru.bitcoin.node.rvn.pow.RvnKawpowWords.writeLE((int) (now - 60 + height), raw, 68);
                ru.bitcoin.node.rvn.pow.RvnKawpowWords.writeLE(height, raw, 76);
            }
            var body = new RvnBlock(parsed(raw), original.transactions()); blocks.add(body);
            parent = new RvnHash256(RvnWire.doubleSha(raw));
        }
        var completed = new CountDownLatch(1); var error = new AtomicReference<Throwable>();
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()); var store = open(temp.resolve("headers-first-" + recent))) {
            var peer = Thread.ofPlatform().daemon(true).start(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(8000); handshake(socket, server.getLocalPort());
                    var in = socket.getInputStream(); var out = socket.getOutputStream();
                    for (var body : blocks) {
                        assertEquals("getheaders", RvnWire.read(in, RvnNetwork.MAINNET).command(), "No bodies before header discovery ends");
                        RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("headers", RvnHeaders.encode(List.of(body.header()))));
                    }
                    assertEquals("getheaders", RvnWire.read(in, RvnNetwork.MAINNET).command());
                    RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("headers", new byte[]{0}));
                    if (recent) {
                        var request = RvnWire.read(in, RvnNetwork.MAINNET); assertEquals("getdata", request.command());
                        var inventory = new ByteArrayInputStream(request.payload()); assertEquals(2, RvnCompactSize.read(inventory, 16));
                        for (var body : blocks) {
                            assertEquals(0x40000002L, RvnWire.readU32(inventory));
                            assertArrayEquals(RvnWire.doubleSha(body.header().serialize()), inventory.readNBytes(32));
                            RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("block", RvnBlockCodec.serialize(body)));
                        }
                        assertEquals("getheaders", RvnWire.read(in, RvnNetwork.MAINNET).command());
                    } else assertEquals(-1, in.read(), "Stale header tip must not start body downloads");
                    completed.countDown(); while (in.read() != -1) { }
                } catch (Throwable failure) { error.set(failure); completed.countDown(); }
            });
            // Test-only proof engine, including the mix contract for recent headers.
            var pow = new ru.bitcoin.node.rvn.pow.RvnKawpowMixEngine() {
                public ru.bitcoin.node.rvn.pow.RvnPowResult calculate(ru.bitcoin.node.rvn.pow.RvnPowAlgorithm algorithm, byte[] raw) {
                    return new ru.bitcoin.node.rvn.pow.RvnPowResult(RvnWire.doubleSha(raw), new byte[32]);
                }
                public ru.bitcoin.node.rvn.pow.RvnKawpowMixResult calculateKawpow(byte[] raw) {
                    return new ru.bitcoin.node.rvn.pow.RvnKawpowMixResult(new byte[32],
                            calculate(ru.bitcoin.node.rvn.pow.RvnPowAlgorithm.KAWPOW, raw));
                }
            };
            var chain = new RvnMainnetHeaderChain(store, pow, () -> now);
            var sync = new RvnHeaderSyncService(chain, List.of("127.0.0.1"), server.getLocalPort(),
                    1000, 3000, 5000, new RvnBlockDownloader(store, 3000, 16), null, new RvnSeedResolver(""), 4096, 4, 64, true);
            try {
                sync.start(); assertTrue(completed.await(12, TimeUnit.SECONDS));
                if (error.get() != null) fail(error.get());
                assertEquals(2, store.best().height());
                assertEquals(recent ? 2 : 0, store.downloadedBodyTip().height());
            } finally { sync.stop(); }
            peer.join(3000); assertFalse(peer.isAlive()); if (error.get() != null) fail(error.get());
        }
    }

    private static RvnBlock block(RvnHash256 parent, int height) throws Exception {
        var payout = RvnPayoutAddress.parse("RXBurnXXXXXXXXXXXXXXXXXXXXXXWUo9FV", RvnNetwork.MAINNET);
        var tx = RvnCoinbase.create(height, 0, 0, payout);
        byte[] raw = child(parent, 1514999494L + 60 * height);
        System.arraycopy(MerkleTree.calculateRoot(List.of(tx.txId())).bytes(), 0, raw, 36, 32);
        return new RvnBlock(parsed(raw), List.of(tx));
    }

    @Test
    void bodyBacklogCannotStarveHeaderRequests() throws Exception {
        var bodies = new HashMap<RvnHash256, RvnBlock>();
        var rawHeaders = new ArrayList<byte[]>();
        var parent = RvnMainnetExpectedBits.GENESIS_ID;
        for (int height = 1; height <= 3073; height++) {
            var body = block(parent, height);
            byte[] raw = body.header().serialize(); rawHeaders.add(raw);
            parent = new RvnHash256(RvnWire.doubleSha(raw)); bodies.put(parent, body);
        }
        var error = new AtomicReference<Throwable>(); var completed = new CountDownLatch(1);
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()); var store = open(temp.resolve("backlog"))) {
            // Synthetic PoW fixture: this test checks scheduling and body commitments.
            for (int offset = 0; offset < rawHeaders.size(); offset += 2000)
                store.accept(rawHeaders.subList(offset, Math.min(offset + 2000, rawHeaders.size())),
                        (raw, branch) -> new RvnHash256(RvnWire.doubleSha(raw)));
            var peer = Thread.ofPlatform().daemon(true).start(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(8000);
                    var in = socket.getInputStream(); var out = socket.getOutputStream();
                    assertEquals("version", RvnWire.read(in, RvnNetwork.MAINNET).command());
                    RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("version", RvnVersion.outbound(70028, server.getLocalPort(), 0)));
                    assertEquals("verack", RvnWire.read(in, RvnNetwork.MAINNET).command());
                    RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("verack", new byte[0]));
                    assertEquals("getheaders", RvnWire.read(in, RvnNetwork.MAINNET).command());
                    RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("headers", new byte[]{0}));
                    for (int i = 0; i < 2048;) {
                        var request = RvnWire.read(in, RvnNetwork.MAINNET);
                        assertEquals("getdata", request.command(), "Prefer up to four body batches between header requests");
                        var inventory = new ByteArrayInputStream(request.payload());
                        int count = (int) RvnCompactSize.read(inventory, 16); assertTrue(count > 0);
                        for (int n = 0; n < count; n++, i++) {
                            assertEquals(0x40000002L, RvnWire.readU32(inventory));
                            var body = bodies.get(new RvnHash256(inventory.readNBytes(32))); assertNotNull(body);
                            RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("block", RvnBlockCodec.serialize(body)));
                        }
                    }
                    assertEquals("getheaders", RvnWire.read(in, RvnNetwork.MAINNET).command());
                    completed.countDown(); while (in.read() != -1) { }
                } catch (Throwable failure) { error.set(failure); completed.countDown(); }
            });
            var chain = new RvnMainnetHeaderChain(store, TEST_POW, () -> 1516000000L);
            var sync = new RvnHeaderSyncService(chain, List.of("127.0.0.1"), server.getLocalPort(),
                    1000, 3000, 5000, new RvnBlockDownloader(store, 3000, 16), null, new RvnSeedResolver(""), 1);
            try {
                sync.start(); assertTrue(completed.await(20, TimeUnit.SECONDS));
                if (error.get() != null) fail(error.get());
                assertEquals(2048, store.downloadedBodyTip().height());
            } finally { sync.stop(); }
            peer.join(3000); assertFalse(peer.isAlive()); if (error.get() != null) fail(error.get());
        }
    }

    @Test
    void slowDeliveryRotatesPeerAndRetainsDownloadedBodies() throws Exception {
        var bodies = new HashMap<RvnHash256, RvnBlock>(); var rawHeaders = new ArrayList<byte[]>();
        var parent = RvnMainnetExpectedBits.GENESIS_ID;
        for (int height = 1; height <= 1025; height++) {
            var body = block(parent, height); byte[] raw = body.header().serialize(); rawHeaders.add(raw);
            parent = new RvnHash256(RvnWire.doubleSha(raw)); bodies.put(parent, body);
        }
        var error = new AtomicReference<Throwable>(); var switched = new CountDownLatch(1);
        try (var server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress()); var store = open(temp.resolve("slow-peer"))) {
            for (int offset = 0; offset < rawHeaders.size(); offset += 2000)
                store.accept(rawHeaders.subList(offset, Math.min(offset + 2000, rawHeaders.size())),
                        (raw, branch) -> new RvnHash256(RvnWire.doubleSha(raw)));
            var peer = Thread.ofPlatform().daemon(true).start(() -> {
                try {
                    try (var socket = server.accept()) {
                        socket.setSoTimeout(8000); socket.setTcpNoDelay(true); handshake(socket, server.getLocalPort());
                        var in = socket.getInputStream(); var out = socket.getOutputStream();
                        assertEquals("getheaders", RvnWire.read(in, RvnNetwork.MAINNET).command());
                        RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("headers", new byte[]{0}));
                        for (int served = 0; served < 1024;) {
                            var request = RvnWire.read(in, RvnNetwork.MAINNET); assertEquals("getdata", request.command());
                            var inventory = new ByteArrayInputStream(request.payload());
                            int count = (int) RvnCompactSize.read(inventory, 16); assertTrue(count > 0);
                            for (int i = 0; i < count; i++, served++) {
                                assertEquals(0x40000002L, RvnWire.readU32(inventory));
                                var body = bodies.get(new RvnHash256(inventory.readNBytes(32))); assertNotNull(body);
                                Thread.sleep(12);
                                RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("block", RvnBlockCodec.serialize(body)));
                            }
                        }
                        assertEquals(-1, in.read(), "Slow peer must be disconnected after two batches");
                    }
                    try (var socket = server.accept()) {
                        socket.setSoTimeout(8000); handshake(socket, server.getLocalPort()); switched.countDown();
                        while (socket.getInputStream().read() != -1) { }
                    }
                } catch (Throwable failure) { error.set(failure); switched.countDown(); }
            });
            var chain = new RvnMainnetHeaderChain(store, TEST_POW, () -> 1516000000L);
            var sync = new RvnHeaderSyncService(chain, List.of("127.0.0.1", "127.0.0.1"), server.getLocalPort(),
                    1000, 3000, 5000, new RvnBlockDownloader(store, 3000, 16), null, new RvnSeedResolver(""), 1);
            try {
                sync.start(); assertTrue(switched.await(25, TimeUnit.SECONDS));
                if (error.get() != null) fail(error.get());
                assertEquals(1024, store.downloadedBodyTip().height());
            } finally { sync.stop(); }
            peer.join(3000); assertFalse(peer.isAlive()); if (error.get() != null) fail(error.get());
        }
    }

    private static void handshake(Socket socket, int port) throws Exception {
        var in = socket.getInputStream(); var out = socket.getOutputStream();
        assertEquals("version", RvnWire.read(in, RvnNetwork.MAINNET).command());
        RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("version", RvnVersion.outbound(70028, port, 0)));
        assertEquals("verack", RvnWire.read(in, RvnNetwork.MAINNET).command());
        RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("verack", new byte[0]));
    }

    @Test
    void headersWitnessGetdataBodiesAndRestartStaySeparateFromUtxoValidation() throws Exception {
        var first = block(RvnMainnetExpectedBits.GENESIS_ID, 1);
        var firstId = new RvnHash256(RvnWire.doubleSha(first.header().serialize()));
        var second = block(firstId, 2);
        var secondId = new RvnHash256(RvnWire.doubleSha(second.header().serialize()));
        var error = new AtomicReference<Throwable>(); var completed = new CountDownLatch(1);
        Path path = temp.resolve("download");
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()); var store = open(path)) {
            var peer = Thread.ofPlatform().daemon(true).start(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(8000);
                    var in = socket.getInputStream(); var out = socket.getOutputStream();
                    assertEquals("version", RvnWire.read(in, RvnNetwork.MAINNET).command());
                    RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("version", RvnVersion.outbound(70028, server.getLocalPort(), 0)));
                    assertEquals("verack", RvnWire.read(in, RvnNetwork.MAINNET).command());
                    RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("verack", new byte[0]));
                    assertEquals("getheaders", RvnWire.read(in, RvnNetwork.MAINNET).command());
                    RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("headers", RvnHeaders.encode(List.of(first.header(), second.header()))));
                    var request = RvnWire.read(in, RvnNetwork.MAINNET); assertEquals("getdata", request.command());
                    var inventory = new ByteArrayInputStream(request.payload());
                    assertEquals(2, RvnCompactSize.read(inventory, 16));
                    for (var block : List.of(first, second)) {
                        assertEquals(0x40000002L, RvnWire.readU32(inventory));
                        assertArrayEquals(RvnWire.doubleSha(block.header().serialize()), inventory.readNBytes(32));
                    }
                    // Serve the child first: durable bodies and the contiguous cursor
                    // must still resume correctly after pipelined, reordered replies.
                    for (var block : List.of(second, first))
                        RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("block", RvnBlockCodec.serialize(block)));
                    assertEquals("getheaders", RvnWire.read(in, RvnNetwork.MAINNET).command());
                    RvnWire.write(out, RvnNetwork.MAINNET, new RvnMessage("headers", new byte[]{0}));
                    completed.countDown(); while (in.read() != -1) { }
                } catch (Throwable failure) { error.set(failure); completed.countDown(); }
            });
            var chain = new RvnMainnetHeaderChain(store, TEST_POW, () -> 1515000000L);
            var sync = new RvnHeaderSyncService(chain, List.of("127.0.0.1"), server.getLocalPort(),
                    1000, 3000, 5000, new RvnBlockDownloader(store, 3000));
            try {
                sync.start(); assertTrue(completed.await(12, TimeUnit.SECONDS));
                if (error.get() != null) fail(error.get());
                assertEquals(2, store.downloadedBodyTip().height());
                assertArrayEquals(RvnBlockCodec.serialize(second), store.blockBody(secondId));
            } finally { sync.stop(); }
            peer.join(3000); assertFalse(peer.isAlive()); if (error.get() != null) fail(error.get());
        }
        try (var store = open(path)) {
            assertNull(store.nextMissingBlockBody()); assertEquals(2, store.downloadedBodyTip().height());
            RvnBlockBodyChecks.requireValid(RvnBlockCodec.parse(store.blockBody(secondId)), false);
        }
    }
}
