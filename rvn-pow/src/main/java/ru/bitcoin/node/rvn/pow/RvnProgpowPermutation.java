package ru.bitcoin.node.rvn.pow;

import java.util.Arrays;

/**
 * Fisher-Yates register permutation driven by KISS99, without shared state.
 */
public final class RvnProgpowPermutation {
    private RvnProgpowPermutation() {
    }

    public static int[] shuffled(int size, RvnProgpowRandom random) {
        if (size < 1 || size > 256 || random == null) throw new IllegalArgumentException("size/random");
        int[] order = new int[size];
        for (int i = 0; i < size; i++) order[i] = i;
        for (int i = size - 1; i > 0; i--) {
            int j = Integer.remainderUnsigned(random.nextInt(), i + 1);
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }
        return order;
    }

    public static void requirePermutation(int[] order, int size) {
        if (order == null || order.length != size) throw new IllegalArgumentException("permutation size");
        boolean[] seen = new boolean[size];
        for (int i : order) {
            if (i < 0 || i >= size || seen[i]) throw new IllegalArgumentException("invalid permutation");
            seen[i] = true;
        }
    }
}
