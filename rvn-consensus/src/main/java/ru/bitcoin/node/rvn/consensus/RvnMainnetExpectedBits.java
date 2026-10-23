package ru.bitcoin.node.rvn.consensus;

import java.util.ArrayList;
import java.util.HexFormat;

/** Adapts mainnet difficulty to the verified, branch-specific header index. */
public final class RvnMainnetExpectedBits implements RvnHeaderContextPolicy.ExpectedBits {
    public static final RvnHash256 GENESIS_ID;
    static {
        byte[] bytes = HexFormat.of().parseHex(
                "0000006b444bc2f2ffe627be9d9e7e7a0730000870ef6eb6da46c8eae389df90");
        for (int i = 0; i < 16; i++) {
            byte v = bytes[i]; bytes[i] = bytes[31 - i]; bytes[31 - i] = v;
        }
        GENESIS_ID = new RvnHash256(bytes);
    }

    @Override
    public long expected(RvnHeaderCandidate candidate, RvnValidatedHeaderIndex index) {
        // Keep one branch snapshot while reading all ancestors, including on a staged index.
        synchronized (index) {
            var history = new ArrayList<RvnMainnetDifficulty.Ancestor>();
            var cursor = new RvnHash256[]{candidate.parentId()};
            return RvnMainnetDifficulty.expected(candidate.timestamp(), distance -> {
                while (history.size() <= distance) {
                    if (cursor[0].equals(GENESIS_ID)) {
                        if (!history.isEmpty() && history.getLast().height() != 1)
                            throw new SecurityException("Mainnet genesis height mismatch");
                        history.add(new RvnMainnetDifficulty.Ancestor(0, 1514999494L, 0x1e00ffffL));
                        cursor[0] = null;
                    } else {
                        if (cursor[0] == null) throw new SecurityException("History before genesis");
                        var entry = index.find(cursor[0]).orElseThrow(
                                () -> new SecurityException("Missing validated mainnet difficulty history"));
                        var c = entry.candidate();
                        history.add(new RvnMainnetDifficulty.Ancestor(entry.height(), c.timestamp(), c.bits()));
                        cursor[0] = c.parentId();
                    }
                }
                return history.get(distance);
            });
        }
    }
}
