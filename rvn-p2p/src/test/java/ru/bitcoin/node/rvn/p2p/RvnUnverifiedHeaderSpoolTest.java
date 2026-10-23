package ru.bitcoin.node.rvn.p2p;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.rvn.protocol.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RvnUnverifiedHeaderSpoolTest {
    @TempDir
    Path dir;
    private static final long ACTIVATION = RvnActivation.MAINNET_KAWPOW_TIME;

    private static RvnHeadersBatch batch(int height) throws IOException {
        byte[] bytes = new byte[120];
        bytes[0] = 4;
        for (int i = 0; i < 4; i++) {
            bytes[68 + i] = (byte) (ACTIVATION >>> (8 * i));
            bytes[76 + i] = (byte) (height >>> (8 * i));
        }
        bytes[72] = 1;
        return RvnHeadersBatch.fromHeaders(List.of(RvnBlockHeader.parse(bytes, ACTIVATION)), ACTIVATION);
    }

    @Test
    void survivesRestartAndDeduplicates() throws Exception {
        var spool = new RvnUnverifiedHeaderSpool(dir, ACTIVATION, 2);
        var one = batch(1);
        String id = spool.store(one);
        assertEquals(id, spool.store(one));
        var reopened = new RvnUnverifiedHeaderSpool(dir, ACTIVATION, 2);
        assertArrayEquals(one.payload(), reopened.load(id).payload());
        assertEquals(1, reopened.list().size());
        reopened.store(batch(2));
        assertThrows(IOException.class, () -> reopened.store(batch(3)));
        assertTrue(reopened.delete(id));
        assertEquals(1, reopened.list().size());
    }

    @Test
    void detectsTampering() throws Exception {
        var spool = new RvnUnverifiedHeaderSpool(dir, ACTIVATION, 2);
        String id = spool.store(batch(7));
        Path file = dir.resolve(id + ".rvnb");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 1;
        Files.write(file, bytes);
        assertThrows(IOException.class, () -> spool.load(id));
        assertThrows(IOException.class, () -> spool.store(batch(7)));
    }

    @Test
    void refusesPathTraversal() throws Exception {
        var spool = new RvnUnverifiedHeaderSpool(dir, ACTIVATION, 2);
        assertThrows(IllegalArgumentException.class, () -> spool.load("../outside"));
    }
}
