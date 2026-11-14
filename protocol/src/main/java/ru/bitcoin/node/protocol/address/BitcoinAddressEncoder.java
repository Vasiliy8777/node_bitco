package ru.bitcoin.node.protocol.address;

import ru.bitcoin.node.protocol.network.*;
import ru.bitcoin.node.crypto.hash.Hash256Digest;
import java.math.BigInteger;
import java.util.*;

/** Base58Check and BIP173/BIP350 destination encoding. */
public final class BitcoinAddressEncoder {
    private BitcoinAddressEncoder() { }
    private static final String BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final String BECH32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int[] GENERATORS = {0x3b6a57b2,0x26508e6d,0x1ea119fa,0x3d4233dd,0x2a1462b3};
    public static String legacy(int version, byte[] data) {
        if (version < 0 || version > 255) throw new IllegalArgumentException("Address version");
        byte[] payload = new byte[data.length + 1]; payload[0] = (byte) version; System.arraycopy(data, 0, payload, 1, data.length);
        byte[] bytes = Arrays.copyOf(payload, payload.length + 4);
        System.arraycopy(Hash256Digest.hash(payload).bytes(), 0, bytes, payload.length, 4);
        var value = new BigInteger(1, bytes); var result = new StringBuilder();
        while (value.signum() != 0) { var pair = value.divideAndRemainder(BigInteger.valueOf(58)); result.append(BASE58.charAt(pair[1].intValue())); value = pair[0]; }
        for (byte b : bytes) { if (b != 0) break; result.append('1'); }
        return result.reverse().toString();
    }
    public static String witness(int version, byte[] program, NetworkParameters parameters) {
        if (version < 0 || version > 16 || program.length < 2 || program.length > 40 || version == 0 && program.length != 20 && program.length != 32)
            throw new IllegalArgumentException("Witness destination");
        String hrp = parameters.network() == BitcoinNetwork.MAINNET ? "bc" : parameters.network() == BitcoinNetwork.REGTEST ? "bcrt" : "tb";
        var data = new ArrayList<Integer>(); data.add(version); int accumulator = 0, bits = 0;
        for (byte b : program) { accumulator = (accumulator << 8) | (b & 255); bits += 8; while (bits >= 5) { bits -= 5; data.add((accumulator >>> bits) & 31); } }
        if (bits != 0) data.add((accumulator << (5 - bits)) & 31);
        int checksum = 1;
        for (int i = 0; i < hrp.length(); i++) checksum = polymod(checksum, hrp.charAt(i) >>> 5);
        checksum = polymod(checksum, 0);
        for (int i = 0; i < hrp.length(); i++) checksum = polymod(checksum, hrp.charAt(i) & 31);
        for (int value : data) checksum = polymod(checksum, value);
        for (int i = 0; i < 6; i++) checksum = polymod(checksum, 0);
        checksum ^= version == 0 ? 1 : 0x2bc830a3;
        var result = new StringBuilder(hrp).append('1');
        for (int value : data) result.append(BECH32.charAt(value));
        for (int i = 0; i < 6; i++) result.append(BECH32.charAt((checksum >>> (5 * (5 - i))) & 31));
        return result.toString();
    }
    private static int polymod(int value, int symbol) {
        int top = value >>> 25, result = ((value & 0x1ffffff) << 5) ^ symbol;
        for (int i = 0; i < 5; i++) if ((top & (1 << i)) != 0) result ^= GENERATORS[i];
        return result;
    }
}
