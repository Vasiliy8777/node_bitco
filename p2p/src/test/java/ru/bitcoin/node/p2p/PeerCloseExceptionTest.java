package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PeerCloseExceptionTest {
    @Test
    void preservesReasonOriginAndCause() {
        Exception cause = new Exception("transport");
        PeerCloseException failure = new PeerCloseException(
                PeerCloseReason.LOCAL_BLOCK_TIMEOUT,
                "SchedulerBlockDownloadSession.failTimedOutPeers",
                "confirmed timeout",
                cause
        );
        assertEquals(PeerCloseReason.LOCAL_BLOCK_TIMEOUT, failure.reason());
        assertEquals("SchedulerBlockDownloadSession.failTimedOutPeers", failure.origin());
        assertEquals(cause, failure.getCause());
    }
}
