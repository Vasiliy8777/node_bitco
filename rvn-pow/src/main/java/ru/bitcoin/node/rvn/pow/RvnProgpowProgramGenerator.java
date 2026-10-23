package ru.bitcoin.node.rvn.pow;

import java.util.ArrayList;

/**
 * Deterministic KISS99-based test program generator.
 * Deliberately NOT a substitute for KawPoW's height-dependent generator.
 */
public final class RvnProgpowProgramGenerator {
    private RvnProgpowProgramGenerator() {
    }

    public static RvnProgpowProgram generate(int seed, int count) {
        if (count < 1 || count > 1024) throw new IllegalArgumentException("count");
        RvnProgpowRandom random = new RvnProgpowRandom(seed, seed ^ 0x9e3779b9, ~seed, seed + 1);
        var ops = new ArrayList<RvnProgpowOperation>(count);
        for (int i = 0; i < count; i++) {
            int dst = Integer.remainderUnsigned(random.nextInt(), 32);
            int src = Integer.remainderUnsigned(random.nextInt(), 32);
            int selector = random.nextInt();
            ops.add(new RvnProgpowOperation((i & 1) == 0 ? RvnProgpowOperation.Kind.MATH :
                    RvnProgpowOperation.Kind.MERGE, dst, src, selector));
        }
        return new RvnProgpowProgram(ops);
    }
}
