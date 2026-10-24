package ru.bitcoin.node.rvn.chain.assets;

import java.nio.charset.StandardCharsets;
import java.util.*;
import ru.bitcoin.node.protocol.serialization.BitcoinReader;

/** Core's positional asset classification and deserialization. This is not the
 * canonical constructor codec or a transaction/script validity check. Original
 * script bytes must be retained for signature hashing and sigop interpretation. */
public final class RvnHistoricalAssetCodec {
    private RvnHistoricalAssetCodec() {}
    public enum Kind { ISSUE, OWNER, TRANSFER, REISSUE }
    public record Classification(Kind kind, int dataOffset) {}
    public record Decoded(Classification classification, String name, long amount, int units,
                          int reissuable, int hasHash, byte[] hash, long expires) {
        public Decoded { hash = hash.clone(); }
        @Override public byte[] hash() { return hash.clone(); }
    }
    public enum NullKind { ADDRESS, GLOBAL, VERIFIER }
    public record NullData(NullKind kind, String addressHash, String name, int flag, String verifier) {}

    public static Optional<Classification> classify(byte[] script) {
        Objects.requireNonNull(script);
        if (script.length <= 31 || (script[25] & 255) != 0xc0) return Optional.empty();
        int type = -1;
        // Match Core's else branch exactly; do not try both locations if [27]=='r'.
        if (script[27] == 'r') {
            if (script[28] == 'v' && script[29] == 'n') type = 30;
        } else if (script[28] == 'r' && script[29] == 'v' && script[30] == 'n') type = 31;
        if (type < 0) return Optional.empty();
        Kind kind = switch (script[type]) {
            case 'q' -> script.length > 39 ? Kind.ISSUE : null;
            case 'o' -> Kind.OWNER;
            case 't' -> Kind.TRANSFER;
            case 'r' -> Kind.REISSUE;
            default -> null;
        };
        return kind == null ? Optional.empty() : Optional.of(new Classification(kind, type + 1));
    }

    public static Optional<Decoded> decode(byte[] script, boolean transferSizeFix) {
        var classification = classify(script);
        if (classification.isEmpty()) return Optional.empty();
        var c = classification.get();
        int offset = c.kind() == Kind.TRANSFER && !transferSizeFix ? 31 : c.dataOffset();
        var in = new BitcoinReader(Arrays.copyOfRange(script, offset, script.length));
        String name = string(in);
        long amount = 0, expires = 0; int units = 0, reissuable = 0, hasHash = 0;
        byte[] hash = new byte[0];
        switch (c.kind()) {
            case OWNER -> amount = 100_000_000L;
            case ISSUE -> {
                amount = in.readInt64LE(); units = (byte) in.readUnsignedByte();
                reissuable = (byte) in.readUnsignedByte(); hasHash = (byte) in.readUnsignedByte();
                if (hasHash == 1) hash = optionalHash(in);
            }
            case TRANSFER -> {
                amount = in.readInt64LE();
                boolean hasOptionalHash = in.remaining() >= 33;
                hash = optionalHash(in);
                if (hasOptionalHash && in.remaining() >= 8) expires = in.readInt64LE();
            }
            case REISSUE -> {
                amount = in.readInt64LE(); units = (byte) in.readUnsignedByte();
                reissuable = (byte) in.readUnsignedByte(); hash = optionalHash(in);
            }
        }
        // Core deliberately does not require the stream to end at the asset data,
        // validate push length, check OP_DROP or validate the first 25 script bytes.
        return Optional.of(new Decoded(c, name, amount, units, reissuable, hasHash, hash, expires));
    }

    public static Optional<NullData> decodeNull(byte[] script) {
        Objects.requireNonNull(script);
        if (script.length == 0 || (script[0] & 255) != 0xc0) return Optional.empty();
        NullKind kind; int offset;
        if (script.length > 23 && script[1] == 0x14) { kind = NullKind.ADDRESS; offset = 23; }
        else if (script.length > 6 && script[1] == 0x50 && script[2] == 0x50) { kind = NullKind.GLOBAL; offset = 4; }
        else if (script.length > 3 && script[1] == 0x50 && script[2] != 0x50) { kind = NullKind.VERIFIER; offset = 3; }
        else return Optional.empty();
        var in = new BitcoinReader(Arrays.copyOfRange(script, offset, script.length));
        if (kind == NullKind.VERIFIER) return Optional.of(new NullData(kind, "", "", -1, string(in)));
        String name = string(in); int flag = (byte) in.readUnsignedByte();
        String address = kind == NullKind.ADDRESS ? HexFormat.of().formatHex(Arrays.copyOfRange(script, 2, 22)) : "";
        return Optional.of(new NullData(kind, address, name, flag, ""));
    }

    private static String string(BitcoinReader in) {
        return new String(in.readCompactBytes(), StandardCharsets.ISO_8859_1);
    }
    private static byte[] optionalHash(BitcoinReader in) {
        if (in.remaining() < 33) return new byte[0];
        int marker = in.readUnsignedByte();
        byte[] bytes = in.readCompactBytes();
        int length = Math.min(32, bytes.length);
        byte[] result = new byte[length + (marker == 0x12 ? 2 : 0)];
        if (marker == 0x12) { result[0] = 0x12; result[1] = 0x20; }
        System.arraycopy(bytes, 0, result, result.length - length, length);
        return result;
    }
}
