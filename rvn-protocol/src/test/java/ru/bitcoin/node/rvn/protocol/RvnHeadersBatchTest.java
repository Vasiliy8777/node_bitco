package ru.bitcoin.node.rvn.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class RvnHeadersBatchTest {
    @Test
    void emptyHeadersMessageIsValidButUntrusted() throws Exception {
        var batch = RvnHeadersBatch.fromPayload(new byte[]{0}, RvnActivation.MAINNET_KAWPOW_TIME);
        assertEquals(0, batch.count());
        assertEquals(32, batch.contentDigest().length);
        byte[] payload = batch.payload();
        payload[0] = 7;
        assertEquals(0, batch.payload()[0]);
    }

    @Test
    void rejectsTrailingGarbage() {
        assertThrows(IOException.class, () -> RvnHeadersBatch.fromPayload(
                new byte[]{0, 1}, RvnActivation.MAINNET_KAWPOW_TIME));
    }
}
