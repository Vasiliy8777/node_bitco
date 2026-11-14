package ru.bitcoin.node.app.rpc;

// Copyright (c) 2018-present The Bitcoin Core developers
// Bitcoin Core descriptor checksum, MIT license: rpc/CORE-LICENSE.txt.
final class DescriptorChecksum {
    private DescriptorChecksum() { }
    private static final String INPUT = "0123456789()[],'/*abcdefgh@:$%{}IJKLMNOPQRSTUVWXYZ&+-.;<=>?!^_|~ijklmnopqrstuvwxyzABCDEFGH`#\"\\ ";
    private static final String OUTPUT = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final long[] GENERATORS = {0xf5dee51989L,0xa9fdca3312L,0x1bab10e32dL,0x3706b1677aL,0x644d626ffdL};
    static String add(String descriptor) {
        long checksum = 1; int group = 0, count = 0;
        for (int i = 0; i < descriptor.length(); i++) {
            int symbol = INPUT.indexOf(descriptor.charAt(i));
            if (symbol < 0) throw new IllegalArgumentException("Invalid descriptor character");
            checksum = polymod(checksum, symbol & 31); group = group * 3 + (symbol >>> 5);
            if (++count == 3) { checksum = polymod(checksum, group); group = 0; count = 0; }
        }
        if (count != 0) checksum = polymod(checksum, group);
        for (int i = 0; i < 8; i++) checksum = polymod(checksum, 0);
        checksum ^= 1;
        var result = new StringBuilder(descriptor).append('#');
        for (int i = 0; i < 8; i++) result.append(OUTPUT.charAt((int) (checksum >>> (5 * (7 - i))) & 31));
        return result.toString();
    }
    private static long polymod(long checksum, int value) {
        long top = checksum >>> 35; checksum = ((checksum & 0x7ffffffffL) << 5) ^ value;
        for (int i = 0; i < 5; i++) if ((top & (1L << i)) != 0) checksum ^= GENERATORS[i];
        return checksum;
    }
}
