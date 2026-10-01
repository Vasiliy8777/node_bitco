package ru.bitcoin.node.protocol.address;

import ru.bitcoin.node.protocol.network.BitcoinNetwork;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/** Decodes native SegWit (Bech32/BIP173 and Bech32m/BIP350) addresses into scriptPubKey. */
public final class SegwitAddressDecoder {
    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int BECH32 = 1;
    private static final int BECH32M = 0x2bc830a3;

    private SegwitAddressDecoder() {}

    public static byte[] toScriptPubKey(String address, NetworkParameters parameters) {
        Objects.requireNonNull(parameters, "parameters");
        Decoded decoded = decode(address);
        String expectedHrp = hrp(parameters.network());
        if (!decoded.hrp().equals(expectedHrp)) {
            throw new IllegalArgumentException("Payout address belongs to network '" + decoded.hrp()
                    + "', expected HRP '" + expectedHrp + "' for " + parameters.network());
        }
        int version = decoded.witnessVersion();
        byte[] program = decoded.witnessProgram();
        byte[] script = new byte[2 + program.length];
        script[0] = version == 0 ? 0x00 : (byte) (0x50 + version); // OP_0 or OP_1..OP_16
        script[1] = (byte) program.length; // witness programs are 2..40 bytes, direct push
        System.arraycopy(program, 0, script, 2, program.length);
        return script;
    }

    public static Decoded decode(String address) {
        if (address == null || address.isBlank()) throw new IllegalArgumentException("Bitcoin address must not be blank");
        String input = address.trim();
        if (input.length() > 90) throw new IllegalArgumentException("Bech32 address exceeds 90 characters");
        boolean lower = !input.equals(input.toUpperCase(Locale.ROOT));
        boolean upper = !input.equals(input.toLowerCase(Locale.ROOT));
        if (lower && upper) throw new IllegalArgumentException("Mixed-case Bech32 address");
        input = input.toLowerCase(Locale.ROOT);

        int separator = input.lastIndexOf('1');
        if (separator < 1 || separator + 7 > input.length()) throw new IllegalArgumentException("Invalid Bech32 separator/checksum");
        String hrp = input.substring(0, separator);
        int[] values = new int[input.length() - separator - 1];
        for (int i = 0; i < values.length; i++) {
            int value = CHARSET.indexOf(input.charAt(separator + 1 + i));
            if (value < 0) throw new IllegalArgumentException("Invalid Bech32 character");
            values[i] = value;
        }

        int checksum = polymod(hrpExpand(hrp), values);
        int encoding = checksum == BECH32 ? BECH32 : checksum == BECH32M ? BECH32M : 0;
        if (encoding == 0) throw new IllegalArgumentException("Invalid Bech32 checksum");

        int[] payload = Arrays.copyOf(values, values.length - 6);
        if (payload.length < 1) throw new IllegalArgumentException("Missing witness version");
        int version = payload[0];
        if (version > 16) throw new IllegalArgumentException("Witness version must be 0..16");
        byte[] program = convertBits(Arrays.copyOfRange(payload, 1, payload.length), 5, 8);
        if (program.length < 2 || program.length > 40) throw new IllegalArgumentException("Witness program must be 2..40 bytes");
        if (version == 0 && program.length != 20 && program.length != 32) {
            throw new IllegalArgumentException("Witness v0 program must be 20 or 32 bytes");
        }
        if (version == 0 && encoding != BECH32) throw new IllegalArgumentException("Witness v0 requires Bech32 checksum");
        if (version != 0 && encoding != BECH32M) throw new IllegalArgumentException("Witness v1+ requires Bech32m checksum");
        return new Decoded(hrp, version, program);
    }

    private static String hrp(BitcoinNetwork network) {
        return switch (network) {
            case MAINNET -> "bc";
            case TESTNET, TESTNET4, SIGNET -> "tb";
            case REGTEST -> "bcrt";
        };
    }

    private static int[] hrpExpand(String hrp) {
        int[] out = new int[hrp.length() * 2 + 1];
        for (int i = 0; i < hrp.length(); i++) out[i] = hrp.charAt(i) >>> 5;
        for (int i = 0; i < hrp.length(); i++) out[hrp.length() + 1 + i] = hrp.charAt(i) & 31;
        return out;
    }

    private static int polymod(int[] prefix, int[] values) {
        int chk = 1;
        for (int v : concat(prefix, values)) {
            int top = chk >>> 25;
            chk = (chk & 0x1ffffff) << 5 ^ v;
            if ((top & 1) != 0) chk ^= 0x3b6a57b2;
            if ((top & 2) != 0) chk ^= 0x26508e6d;
            if ((top & 4) != 0) chk ^= 0x1ea119fa;
            if ((top & 8) != 0) chk ^= 0x3d4233dd;
            if ((top & 16) != 0) chk ^= 0x2a1462b3;
        }
        return chk;
    }

    private static int[] concat(int[] a, int[] b) {
        int[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] convertBits(int[] input, int fromBits, int toBits) {
        int acc = 0, bits = 0, maxv = (1 << toBits) - 1;
        byte[] tmp = new byte[(input.length * fromBits) / toBits + 1];
        int count = 0;
        for (int value : input) {
            if (value < 0 || (value >>> fromBits) != 0) throw new IllegalArgumentException("Invalid Bech32 data value");
            acc = (acc << fromBits) | value;
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                tmp[count++] = (byte) ((acc >>> bits) & maxv);
            }
        }
        if (bits >= fromBits || ((acc << (toBits - bits)) & maxv) != 0) {
            throw new IllegalArgumentException("Invalid Bech32 padding");
        }
        return Arrays.copyOf(tmp, count);
    }

    public record Decoded(String hrp, int witnessVersion, byte[] witnessProgram) {
        public Decoded {
            witnessProgram = witnessProgram.clone();
        }
        @Override public byte[] witnessProgram() { return witnessProgram.clone(); }
    }
}
