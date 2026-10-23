package ru.bitcoin.node.rvn.pow;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RvnStages91To100Test {
    @Test
    void f800RejectsMalformedAndDoesNotMutate() {
        assertThrows(IllegalArgumentException.class, () -> RvnKeccakF800.permute(new int[24]));
        assertThrows(IllegalArgumentException.class, () -> RvnKeccakF800.permuteBytes(new byte[99]));
        int[] input = new int[25];
        int[] output = RvnKeccakF800.permute(input);
        assertArrayEquals(new int[25], input);
        assertEquals(25, output.length);
        assertArrayEquals(RvnKawpowWords.encode(output), RvnKeccakF800.permuteBytes(new byte[100]));
        assertFalse(Arrays.equals(input, output));
    }

    @Test
    void f800ZeroKnownAnswer() {
        int[] expected = {0xe531d45d, 0xf404c6fb, 0x23a0bf99, 0xf1f8452f, 0x51ffd042,
                0xe539f578, 0xf00b80a7, 0xaf973664, 0xbf5af34c, 0x227a2424,
                0x88172715, 0x9f685884, 0xb15cd054, 0x1bf4fc0e, 0x6166fa91,
                0x1a9e599a, 0xa3970a1f, 0xab659687, 0xafab8d68, 0xe74b1015,
                0x34001a98, 0x4119eff3, 0x930a0e76, 0x87b28070, 0x11efe996};
        assertArrayEquals(expected, RvnKeccakF800.permute(new int[25]));
    }

    @Test
    void unsignedArithmeticAndEpochBoundaries() {
        assertEquals(0xfffffffe, RvnKawpowMath.mulHiUnsigned(-1, -1));
        assertEquals(0, RvnKawpowMath.mulHiUnsigned(0x7fffffff, 1));
        assertEquals(32, RvnKawpowMath.clz(0));
        assertEquals(32, RvnKawpowMath.popcount(-1));
        assertEquals(0, RvnKawpowEpoch.fromHeight(7499));
        assertEquals(1, RvnKawpowEpoch.fromHeight(7500));
        assertEquals(15000, RvnKawpowEpoch.startHeight(2));
        assertThrows(IllegalArgumentException.class, () -> RvnKawpowEpoch.fromHeight(-1));
    }

    @Test
    void headerViewAndMixReducer() {
        byte[] raw = new byte[120];
        raw[76] = 0x4c;
        raw[77] = 0x1d; // 7500
        raw[80] = 1;
        raw[87] = (byte) 0x80;
        raw[88] = 42;
        var header = new RvnKawpowHeaderView(raw);
        assertEquals(7500, header.declaredHeight());
        assertEquals(1, header.epoch());
        assertEquals(0x8000000000000001L, header.nonceBits());
        raw[88] = 0;
        assertEquals(42, header.mixHashWire()[0]);
        assertEquals(32, RvnKawpowMixReducer.reduce(new int[16][32]).length);
        assertThrows(IllegalArgumentException.class, () -> RvnKawpowMixReducer.reduce(new int[15][32]));
    }
}
