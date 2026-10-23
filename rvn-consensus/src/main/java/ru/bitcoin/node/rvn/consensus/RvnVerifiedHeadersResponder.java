package ru.bitcoin.node.rvn.consensus;

import ru.bitcoin.node.rvn.protocol.RvnBlockHeader;
import ru.bitcoin.node.rvn.protocol.RvnGetHeaders;
import ru.bitcoin.node.rvn.protocol.RvnHeaders;

import java.io.IOException;
import java.util.*;

/**
 * Serves headers ONLY from the validated active chain, never from an
 * unverified peer spool or a stale fork. Does not initiate network IO.
 */
public final class RvnVerifiedHeadersResponder {
    private RvnVerifiedHeadersResponder() {
    }

    public static byte[] reply(RvnValidatedHeaderIndex index, RvnHash256 anchor,
                               byte[] getheadersPayload, long activationTime) throws IOException {
        Objects.requireNonNull(index);
        Objects.requireNonNull(anchor);
        var request = RvnGetHeaders.decode(getheadersPayload);
        // Snapshot the active chain once; no fork header is eligible for reply.
        List<RvnValidatedHeaderIndex.ActiveHeader> chain = index.activeHeadersSnapshot();
        Map<RvnHash256, Integer> positions = new HashMap<>(chain.size());
        for (int i = 0; i < chain.size(); i++) positions.put(chain.get(i).id(), i);
        int start = -1;
        for (byte[] wire : request.locators()) {
            RvnHash256 hash = new RvnHash256(wire);
            Integer position = positions.get(hash);
            if (position != null) {
                start = position + 1;
                break;
            }
            if (hash.equals(anchor)) {
                start = 0;
                break;
            }
        }
        // No common locator: do not pretend to know the remote fork point.
        if (start < 0) return RvnHeaders.encode(List.of());
        List<RvnBlockHeader> headers = new ArrayList<>(Math.min(RvnHeaders.MAX_HEADERS, chain.size() - start));
        byte[] stop = request.stopHash();
        for (int i = start; i < chain.size() && headers.size() < RvnHeaders.MAX_HEADERS; i++) {
            var entry = chain.get(i);
            byte[] raw = entry.raw();
            headers.add(RvnBlockHeader.parse(raw, activationTime));
            if (Arrays.equals(entry.id().wire(), stop)) break;
        }
        return RvnHeaders.encode(headers);
    }
}
