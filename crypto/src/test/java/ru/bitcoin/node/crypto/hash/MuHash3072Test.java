package ru.bitcoin.node.crypto.hash;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MuHash3072Test {
    @Test
    void matchesBitcoinCoreCanonicalVector() {
        byte[] zero = new byte[32];
        byte[] one = new byte[32]; one[0] = 1;
        byte[] two = new byte[32]; two[0] = 2;
        var muhash = new MuHash3072().insert(zero).insert(one).remove(two);
        assertEquals("10d312b100cbd32ada024a6646e40d3482fcff103668d2625f10002a607d5863",
                muhash.finalizeHash().toDisplayHex());
    }

    @Test
    void insertAndRemoveCancelIndependentlyOfOrder() {
        byte[] a = new byte[]{1,2,3};
        byte[] b = new byte[]{4,5,6};
        var left = new MuHash3072().insert(a).insert(b).remove(a).finalizeHash();
        var right = new MuHash3072().insert(b).finalizeHash();
        assertEquals(right, left);
    }
}
