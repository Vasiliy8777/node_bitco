package ru.bitcoin.node.rvn.consensus;

import ru.bitcoin.node.rvn.protocol.RvnGetHeaders;
import ru.bitcoin.node.rvn.protocol.RvnHeaders;

import java.io.IOException;
import java.util.*;

/**
 * Builds successive getheaders requests and decodes responses without ever
 * promoting remote headers to verified status. Caller MUST independently
 * verify all returned headers before selecting the next locator.
 */
public final class RvnVerifiedHeadersPage {
    private RvnVerifiedHeadersPage() {
    }

    public static byte[] request(RvnValidatedHeaderIndex index, RvnHash256 anchor,
                                 int protocolVersion, int locatorLimit) {
        Objects.requireNonNull(index);
        Objects.requireNonNull(anchor);
        return RvnGetHeaders.encode(protocolVersion,
                RvnHeaderLocatorPlan.build(index, index.bestId(), anchor, locatorLimit),
                new byte[32]);
    }

    /**
     * Only a full 2000-header response indicates another page MAY be needed.
     * A shorter response is not proof that the peer is fully synchronized.
     */
    public static boolean mayHaveMore(byte[] headersPayload, long activationTime)
            throws IOException {
        return RvnHeaders.decode(headersPayload, activationTime).size() == RvnHeaders.MAX_HEADERS;
    }
}
