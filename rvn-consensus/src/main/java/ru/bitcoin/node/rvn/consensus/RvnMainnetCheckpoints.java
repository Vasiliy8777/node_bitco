package ru.bitcoin.node.rvn.consensus;

import java.util.HexFormat;

/** Mainnet recovery checkpoint from RavenProject/Ravencoin v4.8.0,
 * src/chainparams.cpp (PR 1289). It never replaces PoW or full block checks. */
public final class RvnMainnetCheckpoints {
    private RvnMainnetCheckpoints() {}
    public static final long RECOVERY_HEIGHT = 4_487_775L;
    public static final RvnHash256 RECOVERY_ID;
    static {
        byte[] raw = HexFormat.of().parseHex("000000000002d64509e06e76ddbbe418c725291687ec62b41ecfc40386a091fd");
        for (int i = 0; i < 16; i++) { byte b = raw[i]; raw[i] = raw[31-i]; raw[31-i] = b; }
        RECOVERY_ID = new RvnHash256(raw);
    }
    public static void require(long height, RvnHash256 id) {
        if (height == RECOVERY_HEIGHT && !RECOVERY_ID.equals(id))
            throw new SecurityException("RVN v4.8.0 mainnet recovery checkpoint mismatch");
    }
}
