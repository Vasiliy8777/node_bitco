package ru.bitcoin.node.rvn.storage;

import java.io.IOException;
import ru.bitcoin.node.rvn.consensus.RvnHash256;

@FunctionalInterface
public interface RvnHeaderLookup {
    /** Returns null for an unknown ID. */
    RvnStoredHeader find(RvnHash256 id) throws IOException;

    default RvnStoredHeader require(RvnHash256 id) throws IOException {
        var header = find(id);
        if (header == null) throw new SecurityException("Unknown RVN header parent: " + id);
        return header;
    }

    default RvnStoredHeader ancestor(RvnStoredHeader tip, long height) throws IOException {
        if (height < 0 || height > tip.height()) throw new IllegalArgumentException("Ancestor height");
        var cursor = tip;
        while (cursor.height() > height) {
            long skipHeight = skipHeight(cursor.height());
            long nextHeight = skipHeight >= height ? skipHeight : cursor.height() - 1;
            var next = require(skipHeight >= height ? cursor.skipId() : cursor.parentId());
            if (next.height() != nextHeight) throw new IOException("Corrupt RVN ancestor link");
            cursor = next;
        }
        return cursor;
    }

    static long skipHeight(long height) {
        if (height < 2) return 0;
        return (height & 1) != 0 ? invert(invert(height - 1)) + 1 : invert(height);
    }
    private static long invert(long value) { return value & (value - 1); }

    default RvnStoredHeader commonAncestor(RvnStoredHeader first, RvnStoredHeader second) throws IOException {
        var a = ancestor(first, Math.min(first.height(), second.height()));
        var b = ancestor(second, a.height());
        while (!a.id().equals(b.id())) {
            if (a.height() == 0) throw new IOException("RVN branches have different genesis");
            var skipA = require(a.skipId()); var skipB = require(b.skipId());
            long height = !skipA.id().equals(skipB.id()) ? skipHeight(a.height()) : a.height() - 1;
            a = ancestor(a, height); b = ancestor(b, height);
        }
        return a;
    }
}
