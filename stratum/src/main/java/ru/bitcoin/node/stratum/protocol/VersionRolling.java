package ru.bitcoin.node.stratum.protocol;

import java.util.HexFormat;
import java.util.Map;

/** BIP310 negotiation; only BIP320 general-purpose bits may be changed. Session-confined. */
public final class VersionRolling {
    public static final int SERVER_MASK = 0x1fffe000;
    private boolean enabled;
    private int mask;

    public boolean enabled() { return enabled; }

    public Map<String, Object> configure(Map<?, ?> parameters) {
        final int requested;
        try {
            Object value = parameters.containsKey("version-rolling.mask")
                    ? parameters.get("version-rolling.mask") : "ffffffff";
            requested = parseMask(value);
        } catch (IllegalArgumentException exception) {
            return error("Expected an eight-digit hexadecimal version-rolling.mask");
        }
        Object minimum = parameters.get("version-rolling.min-bit-count");
        if (!(minimum instanceof Integer || minimum instanceof Long || minimum instanceof java.math.BigInteger))
            return error("Expected a nonnegative integer version-rolling.min-bit-count");
        var count = new java.math.BigInteger(minimum.toString());
        if (count.signum() < 0) return error("Expected a nonnegative integer version-rolling.min-bit-count");
        int negotiated = requested & SERVER_MASK;
        if (count.compareTo(java.math.BigInteger.valueOf(Integer.bitCount(negotiated))) > 0)
            return error("Insufficient version bits available");
        if (enabled && mask != negotiated) return error("Version mask cannot change within a session");
        mask = negotiated;
        enabled = true;
        return Map.of("version-rolling", true, "version-rolling.mask", String.format("%08x", mask));
    }

    public int version(int jobVersion, String bits) {
        if (!enabled) throw new StratumException(20, "Version rolling has not been negotiated");
        int value = parseMask(bits);
        if ((value & ~mask) != 0) throw new StratumException(20, "Version bits outside negotiated mask");
        return (jobVersion & ~mask) | value;
    }

    private static int parseMask(Object value) {
        if (!(value instanceof String text) || text.length() != 8)
            throw new IllegalArgumentException("Expected uint32 hex");
        HexFormat.of().parseHex(text);
        return Integer.parseUnsignedInt(text, 16);
    }

    private static Map<String, Object> error(String message) { return Map.of("version-rolling", message); }
}
