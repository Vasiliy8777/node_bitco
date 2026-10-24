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
    private static RvnBlock block(RvnHash256 parent, int height) throws Exception {
        var payout = RvnPayoutAddress.parse("RXBurnXXXXXXXXXXXXXXXXXXXXXXWUo9FV", RvnNetwork.MAINNET);
        var tx = RvnCoinbase.create(height, 0, 0, payout);
        byte[] raw = child(parent, 1514999494L + 60 * height);
        System.arraycopy(MerkleTree.calculateRoot(List.of(tx.txId())).bytes(), 0, raw, 36, 32);
        return new RvnBlock(parsed(raw), List.of(tx));
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
                    for (var block : List.of(first, second)) {
                        var request = RvnWire.read(in, RvnNetwork.MAINNET); assertEquals("getdata", request.command());
                        var inventory = new ByteArrayInputStream(request.payload());
                        assertEquals(1, RvnCompactSize.read(inventory, 1));
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
