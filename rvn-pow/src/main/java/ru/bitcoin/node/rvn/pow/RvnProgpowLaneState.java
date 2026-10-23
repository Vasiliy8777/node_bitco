package ru.bitcoin.node.rvn.pow;

import java.util.Arrays;

/**
 * Mutable 16 x 32-word lane state; all public array boundaries are defensive.
 */
public final class RvnProgpowLaneState {
    public static final int LANES = 16, REGISTERS = 32;
    private final int[][] lanes = new int[LANES][REGISTERS];

    public RvnProgpowLaneState(int[][] initial) {
        if (initial == null || initial.length != LANES) throw new IllegalArgumentException("16 lanes required");
        for (int i = 0; i < LANES; i++) {
            if (initial[i] == null || initial[i].length != REGISTERS)
                throw new IllegalArgumentException("32 registers per lane");
            System.arraycopy(initial[i], 0, lanes[i], 0, REGISTERS);
        }
    }

    public int get(int lane, int register) {
        check(lane, register);
        return lanes[lane][register];
    }

    public void set(int lane, int register, int value) {
        check(lane, register);
        lanes[lane][register] = value;
    }

    public void merge(int lane, int register, int value, int selector) {
        check(lane, register);
        lanes[lane][register] = RvnKawpowMath.merge(lanes[lane][register], value, selector);
    }

    public void math(int lane, int destination, int source, int selector) {
        check(lane, destination);
        check(lane, source);
        lanes[lane][destination] = RvnProgpowMath.apply(lanes[lane][destination], lanes[lane][source], selector);
    }

    public byte[] reducedMix() {
        return RvnKawpowMixReducer.reduce(snapshot());
    }

    public int[][] snapshot() {
        int[][] result = new int[LANES][];
        for (int i = 0; i < LANES; i++) result[i] = lanes[i].clone();
        return result;
    }

    private static void check(int lane, int register) {
        if (lane < 0 || lane >= LANES || register < 0 || register >= REGISTERS)
            throw new IndexOutOfBoundsException("lane/register");
    }
}
