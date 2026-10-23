package ru.bitcoin.node.rvn.p2p;

import ru.bitcoin.node.rvn.protocol.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * One bounded getheaders round-trip over an already handshaken session.
 */
public final class RvnHeadersClient {
    /**
     * Prevents an active peer from feeding unlimited unrelated messages.
     */
    public static final int MAX_IGNORED_MESSAGES = 64;

    private RvnHeadersClient() {
    }

    public static List<RvnBlockHeader> request(RvnPeerSession session, int protocolVersion,
                                               List<byte[]> locator, byte[] stopHash, long activationTime, long timeoutMs)
            throws IOException, InterruptedException {
        if (session == null) throw new IllegalArgumentException("session");
        if (timeoutMs < 1 || timeoutMs > 120_000) throw new IllegalArgumentException("timeoutMs");
        if (protocolVersion <= 0) throw new IllegalArgumentException("protocolVersion");
        int negotiatedVersion = Math.min(protocolVersion, session.peerVersion());
        session.requestHeaders(negotiatedVersion, locator, stopHash);
        int ignored = 0;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("Timed out waiting for RVN headers");
            RvnMessage message = session.poll(Math.max(1, Math.min(1000,
                    TimeUnit.NANOSECONDS.toMillis(remaining))));
            if (message == null) continue;
            if ("headers".equals(message.command())) {
                List<RvnBlockHeader> headers = RvnHeaders.decode(message.payload(), activationTime);
                RvnHeaderRules.checkBatch(headers);
                return headers;
            }
            // Ignore a bounded number of unrelated messages; do not let a peer
            // indefinitely flood us while waiting for the requested response.
            if (++ignored > MAX_IGNORED_MESSAGES)
                throw new IOException("Too many unrelated messages while awaiting RVN headers");
        }
    }
}
