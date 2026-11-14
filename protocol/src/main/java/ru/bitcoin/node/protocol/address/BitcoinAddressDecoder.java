package ru.bitcoin.node.protocol.address;

import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.protocol.network.BitcoinNetwork;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.math.BigInteger;
import java.util.Arrays;

/** Destination decoding with Core's distinct Base58 and Bech32 whitespace rules. */
public final class BitcoinAddressDecoder {
    private static final String BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private BitcoinAddressDecoder() { }

    public static byte[] toScriptPubKey(String address, NetworkParameters parameters) {
        if (address == null || address.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid address");
        if (address.length() <= 90 && address.equals(asciiTrim(address))) {
            try { return SegwitAddressDecoder.toScriptPubKey(address, parameters); }
            catch (IllegalArgumentException ignored) { }
        }
        String text = asciiTrim(address);
        if (text.isEmpty() || text.length() > 35) throw new IllegalArgumentException("Invalid address");
        BigInteger number = BigInteger.ZERO;
        for (int i = 0; i < text.length(); i++) {
            int digit = BASE58.indexOf(text.charAt(i));
            if (digit < 0) throw new IllegalArgumentException("Invalid address");
            number = number.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));
        }
        byte[] magnitude = number.toByteArray();
        int start = magnitude.length > 0 && magnitude[0] == 0 ? 1 : 0;
        int zeroes = 0;
        while (zeroes < text.length() && text.charAt(zeroes) == '1') zeroes++;
        byte[] decoded = new byte[zeroes + magnitude.length - start];
        System.arraycopy(magnitude, start, decoded, zeroes, magnitude.length - start);
        if (decoded.length != 25) throw new IllegalArgumentException("Invalid address");
        byte[] payload = Arrays.copyOf(decoded, 21);
        if (!Arrays.equals(Arrays.copyOf(Hash256Digest.hashBytes(payload), 4), Arrays.copyOfRange(decoded, 21, 25)))
            throw new IllegalArgumentException("Invalid address");
        boolean main = parameters.network() == BitcoinNetwork.MAINNET;
        int version = decoded[0] & 255;
        byte[] hash = Arrays.copyOfRange(decoded, 1, 21);
        if (version == (main ? 0 : 111)) {
            byte[] script = new byte[25]; script[0] = 0x76; script[1] = (byte) 0xa9; script[2] = 20;
            System.arraycopy(hash, 0, script, 3, 20); script[23] = (byte) 0x88; script[24] = (byte) 0xac;
            return script;
        }
        if (version == (main ? 5 : 196)) {
            byte[] script = new byte[23]; script[0] = (byte) 0xa9; script[1] = 20;
            System.arraycopy(hash, 0, script, 2, 20); script[22] = (byte) 0x87;
            return script;
        }
        throw new IllegalArgumentException("Invalid address");
    }

    private static String asciiTrim(String text) {
        int from = 0, to = text.length();
        while (from < to && whitespace(text.charAt(from))) from++;
        while (to > from && whitespace(text.charAt(to - 1))) to--;
        return text.substring(from, to);
    }

    private static boolean whitespace(char c) { return c == ' ' || c >= '\t' && c <= '\r'; }
}
