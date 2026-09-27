package ru.bitcoin.node.crypto.hash;

import ru.bitcoin.node.common.types.Hash256;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/** Bitcoin Core compatible MuHash3072 multiset accumulator. */
public final class MuHash3072 {
    private static final int BYTE_SIZE = 384;
    private static final BigInteger MODULUS = BigInteger.ONE.shiftLeft(3072).subtract(BigInteger.valueOf(1_103_717L));
    private BigInteger numerator = BigInteger.ONE;
    private BigInteger denominator = BigInteger.ONE;

    public MuHash3072 insert(byte[] data) {
        numerator = numerator.multiply(toElement(data)).mod(MODULUS);
        return this;
    }

    public MuHash3072 remove(byte[] data) {
        denominator = denominator.multiply(toElement(data)).mod(MODULUS);
        return this;
    }

    /** Final commitment without mutating the accumulator. */
    public Hash256 finalizeHash() {
        BigInteger value = numerator.multiply(denominator.modInverse(MODULUS)).mod(MODULUS);
        return new Hash256(sha256(toLittleEndian(value, BYTE_SIZE)));
    }

    private static BigInteger toElement(byte[] data) {
        Objects.requireNonNull(data, "data");
        byte[] key = sha256(data);
        byte[] stream = new byte[BYTE_SIZE];
        for (int block = 0; block < BYTE_SIZE / 64; block++) {
            byte[] bytes = chacha20Block(key, block);
            System.arraycopy(bytes, 0, stream, block * 64, 64);
        }
        return fromLittleEndian(stream).mod(MODULUS);
    }

    private static byte[] chacha20Block(byte[] key, int counter) {
        if (key.length != 32) throw new IllegalArgumentException("ChaCha20 key must be 32 bytes");
        int[] initial = new int[16];
        initial[0] = 0x61707865; initial[1] = 0x3320646e; initial[2] = 0x79622d32; initial[3] = 0x6b206574;
        for (int i = 0; i < 8; i++) initial[4 + i] = readIntLE(key, i * 4);
        initial[12] = counter; // Core ToNum3072 uses nonce 0 and starts at counter 0.
        initial[13] = 0; initial[14] = 0; initial[15] = 0;
        int[] x = initial.clone();
        for (int i = 0; i < 10; i++) {
            quarterRound(x, 0, 4, 8, 12); quarterRound(x, 1, 5, 9, 13);
            quarterRound(x, 2, 6, 10, 14); quarterRound(x, 3, 7, 11, 15);
            quarterRound(x, 0, 5, 10, 15); quarterRound(x, 1, 6, 11, 12);
            quarterRound(x, 2, 7, 8, 13); quarterRound(x, 3, 4, 9, 14);
        }
        byte[] out = new byte[64];
        for (int i = 0; i < 16; i++) writeIntLE(out, i * 4, x[i] + initial[i]);
        return out;
    }

    private static void quarterRound(int[] x, int a, int b, int c, int d) {
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] ^ x[a], 16);
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] ^ x[c], 12);
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] ^ x[a], 8);
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] ^ x[c], 7);
    }

    private static int readIntLE(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | (b[o + 3] << 24);
    }

    private static void writeIntLE(byte[] b, int o, int v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >>> 8); b[o + 2] = (byte) (v >>> 16); b[o + 3] = (byte) (v >>> 24);
    }

    private static BigInteger fromLittleEndian(byte[] bytes) {
        byte[] be = bytes.clone();
        reverse(be);
        return new BigInteger(1, be);
    }

    private static byte[] toLittleEndian(BigInteger value, int size) {
        byte[] be = value.toByteArray();
        if (be.length > size + 1 || (be.length == size + 1 && be[0] != 0)) throw new IllegalStateException("MuHash value overflow");
        if (be.length == size + 1) be = Arrays.copyOfRange(be, 1, be.length);
        byte[] out = new byte[size];
        for (int i = 0; i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }

    private static void reverse(byte[] bytes) {
        for (int i = 0, j = bytes.length - 1; i < j; i++, j--) {
            byte t = bytes[i]; bytes[i] = bytes[j]; bytes[j] = t;
        }
    }

    private static byte[] sha256(byte[] data) {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
}
