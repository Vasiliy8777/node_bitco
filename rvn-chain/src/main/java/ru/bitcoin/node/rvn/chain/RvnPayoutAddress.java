package ru.bitcoin.node.rvn.chain;

import ru.bitcoin.node.rvn.protocol.*;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.*;

/** Legacy RVN P2PKH/P2SH Base58Check payout. Never retains or prints the supplied address. */
public final class RvnPayoutAddress {
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    public enum Type { P2PKH, P2SH }
    private final Type type;
    private final byte[] script;
    private RvnPayoutAddress(Type type, byte[] script) { this.type = type; this.script = script; }

    public static RvnPayoutAddress parse(String supplied, RvnNetwork network) {
        Objects.requireNonNull(network);
        if (supplied == null) throw invalid();
        String address = supplied.strip();
        if (address.length() < 26 || address.length() > 50) throw invalid();
        BigInteger number = BigInteger.ZERO;
        for (int i = 0; i < address.length(); i++) {
            int digit = ALPHABET.indexOf(address.charAt(i));
            if (digit < 0) throw invalid();
            number = number.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));
        }
        byte[] magnitude = number.toByteArray();
        int offset = magnitude.length > 1 && magnitude[0] == 0 ? 1 : 0;
        int leading = 0; while (leading < address.length() && address.charAt(leading) == '1') leading++;
        int size = leading + magnitude.length - offset;
        if (size != 25) throw invalid();
        byte[] decoded = new byte[size];
        System.arraycopy(magnitude, offset, decoded, leading, magnitude.length - offset);
        byte[] checksum = Arrays.copyOf(RvnWire.doubleSha(Arrays.copyOf(decoded, 21)), 4);
        if (!MessageDigest.isEqual(checksum, Arrays.copyOfRange(decoded, 21, 25))) throw invalid();
        int version = decoded[0] & 255;
        int publicKeyVersion = network == RvnNetwork.MAINNET ? 60 : 111;
        int scriptVersion = network == RvnNetwork.MAINNET ? 122 : 196;
        if (version == publicKeyVersion) {
            byte[] script = new byte[25]; script[0] = 0x76; script[1] = (byte) 0xa9; script[2] = 20;
            System.arraycopy(decoded, 1, script, 3, 20); script[23] = (byte) 0x88; script[24] = (byte) 0xac;
            return new RvnPayoutAddress(Type.P2PKH, script);
        }
        if (version == scriptVersion) {
            byte[] script = new byte[23]; script[0] = (byte) 0xa9; script[1] = 20;
            System.arraycopy(decoded, 1, script, 2, 20); script[22] = (byte) 0x87;
            return new RvnPayoutAddress(Type.P2SH, script);
        }
        throw new IllegalArgumentException("RVN_MINING_ADDRESS belongs to a different network or unsupported address type");
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("RVN_MINING_ADDRESS must be a valid RVN Base58Check P2PKH/P2SH address");
    }
    public Type type() { return type; }
    public byte[] scriptPubKey() { return script.clone(); }
    @Override public String toString() { return "RvnPayoutAddress[type=" + type + "]"; }
}
