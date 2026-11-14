package ru.bitcoin.node.protocol.address;

import ru.bitcoin.node.crypto.hash.Hash256Digest;
import java.math.BigInteger;
import java.util.Arrays;

/** Bounded Base58Check decoding, including Bitcoin's ASCII whitespace rules. */
public final class Base58Check {
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private Base58Check() { }

    public static byte[] decode(String value, int maximumPayload) {
        if (value == null || value.indexOf('\0') >= 0 || maximumPayload < 0) throw new IllegalArgumentException("Invalid Base58Check");
        int from = 0, to = value.length();
        while (from < to && whitespace(value.charAt(from))) from++;
        while (to > from && whitespace(value.charAt(to - 1))) to--;
        if (to == from || to - from > ((long) maximumPayload + 4) * 138 / 100 + 1) throw new IllegalArgumentException("Invalid Base58Check");
        BigInteger integer = BigInteger.ZERO;
        for (int i = from; i < to; i++) {
            int digit = ALPHABET.indexOf(value.charAt(i));
            if (digit < 0) throw new IllegalArgumentException("Invalid Base58Check");
            integer = integer.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));
        }
        byte[] magnitude = integer.toByteArray();
        int start = magnitude[0] == 0 ? 1 : 0, zeroes = 0;
        while (from + zeroes < to && value.charAt(from + zeroes) == '1') zeroes++;
        int length = zeroes + magnitude.length - start;
        if (length < 4 || length > maximumPayload + 4) throw new IllegalArgumentException("Invalid Base58Check");
        byte[] decoded = new byte[length]; System.arraycopy(magnitude, start, decoded, zeroes, magnitude.length - start);
        byte[] payload = Arrays.copyOf(decoded, length - 4);
        if (!Arrays.equals(Arrays.copyOf(Hash256Digest.hashBytes(payload), 4), Arrays.copyOfRange(decoded, length - 4, length))) {
            Arrays.fill(payload, (byte) 0); Arrays.fill(decoded, (byte) 0); Arrays.fill(magnitude, (byte) 0);
            throw new IllegalArgumentException("Invalid Base58Check");
        }
        Arrays.fill(decoded, (byte) 0); Arrays.fill(magnitude, (byte) 0);
        return payload;
    }
    private static boolean whitespace(char c) { return c == ' ' || c >= '\t' && c <= '\r'; }
}
