package ru.bitcoin.node.rvn.chain.assets;

import ru.bitcoin.node.protocol.serialization.BitcoinReader;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Strict codec for canonical P2PKH asset scripts produced by Core constructors.
 * NOT a complete consensus classifier: legacy noncanonical envelopes and null
 * restricted/qualifier scripts require separate handling before block admission.
 * Decoding does not validate names, monetary fields or transaction/state rules. */
public final class RvnAssetScriptCodec {
    private RvnAssetScriptCodec() {}
    public sealed interface Payload permits Issue, Owner, Transfer, Reissue { String name(); }
    public record HashData(int marker, String digestHex) {
        public HashData {
            if ((marker != 0x12 && marker != 0x54) || digestHex == null || digestHex.length() != 64)
                throw new IllegalArgumentException("RVN asset metadata hash");
            HexFormat.of().parseHex(digestHex); digestHex = digestHex.toLowerCase(Locale.ROOT);
        }
    }
    public record Issue(String name, long amount, int units, boolean reissuable, HashData hash) implements Payload {}
    public record Owner(String name) implements Payload {}
    public record Transfer(String name, long amount, HashData message, long expires) implements Payload {}
    public record Reissue(String name, long amount, int units, boolean reissuable, HashData hash) implements Payload {}
    public record Decoded(byte[] lockingScript, Payload payload) {
        public Decoded { lockingScript = lockingScript.clone(); Objects.requireNonNull(payload); }
        @Override public byte[] lockingScript() { return lockingScript.clone(); }
    }

    public static Optional<Decoded> decodeCanonical(byte[] script) {
        Objects.requireNonNull(script);
        if (script.length > 0 && (script[0] & 255) == 0xc0)
            throw new UnsupportedOperationException("RVN null asset script not supported by canonical codec");
        if (script.length < 26 || !p2pkh(Arrays.copyOf(script, 25)) || (script[25] & 255) != 0xc0)
            return Optional.empty();
        var envelope = new BitcoinReader(Arrays.copyOfRange(script, 26, script.length));
        int push = envelope.readUnsignedByte();
        int size;
        if (push <= 75) size = push;
        else if (push == 76) {
            size = envelope.readUnsignedByte();
            if (size <= 75) throw new IllegalArgumentException("Nonminimal RVN asset push");
        } else throw new IllegalArgumentException("Unsupported RVN asset push envelope");
        if (size > 520) throw new IllegalArgumentException("RVN asset payload too large");
        byte[] bytes = envelope.readBytes(size);
        if (envelope.readUnsignedByte() != 0x75 || envelope.hasRemaining())
            throw new IllegalArgumentException("RVN asset envelope must end with OP_DROP");
        var in = new BitcoinReader(bytes);
        if (in.readUnsignedByte() != 'r' || in.readUnsignedByte() != 'v' || in.readUnsignedByte() != 'n')
            throw new IllegalArgumentException("RVN asset marker");
        int kind = in.readUnsignedByte(); String name = readName(in);
        Payload payload = switch (kind) {
            case 'q' -> {
                long amount = in.readInt64LE(); int units = (byte) in.readUnsignedByte();
                boolean reissuable = readBoolean(in), hasHash = readBoolean(in);
                yield new Issue(name, amount, units, reissuable, hasHash ? readHash(in) : null);
            }
            case 'o' -> new Owner(name);
            case 't' -> {
                long amount = in.readInt64LE(); HashData hash = in.hasRemaining() ? readHash(in) : null;
                long expires = in.hasRemaining() ? in.readInt64LE() : 0;
                yield new Transfer(name, amount, hash, expires);
            }
            case 'r' -> new Reissue(name, in.readInt64LE(), (byte) in.readUnsignedByte(), readBoolean(in),
                    in.hasRemaining() ? readHash(in) : null);
            default -> throw new UnsupportedOperationException("Unknown RVN asset payload type");
        };
        if (in.hasRemaining()) throw new IllegalArgumentException("Trailing RVN asset payload bytes");
        return Optional.of(new Decoded(Arrays.copyOf(script, 25), payload));
    }

    public static byte[] encodeCanonical(byte[] lockingScript, Payload payload) {
        if (!p2pkh(lockingScript)) throw new IllegalArgumentException("Canonical RVN asset requires P2PKH locking script");
        var body = new ByteArrayOutputStream(); body.writeBytes(new byte[]{'r', 'v', 'n'});
        body.write(payload instanceof Issue ? 'q' : payload instanceof Owner ? 'o' : payload instanceof Transfer ? 't' : 'r');
        writeName(body, payload.name());
        if (payload instanceof Issue issue) {
            writeLong(body, issue.amount()); writeUnits(body, issue.units()); body.write(issue.reissuable() ? 1 : 0);
            body.write(issue.hash() == null ? 0 : 1); writeHash(body, issue.hash());
        } else if (payload instanceof Transfer transfer) {
            writeLong(body, transfer.amount()); writeHash(body, transfer.message());
            if (transfer.expires() != 0) {
                if (transfer.message() == null) throw new IllegalArgumentException("RVN expiration requires message");
                writeLong(body, transfer.expires());
            }
        } else if (payload instanceof Reissue reissue) {
            writeLong(body, reissue.amount()); writeUnits(body, reissue.units());
            body.write(reissue.reissuable() ? 1 : 0); writeHash(body, reissue.hash());
        }
        byte[] bytes = body.toByteArray();
        if (bytes.length > 255) throw new IllegalArgumentException("RVN canonical asset payload limit");
        var out = new ByteArrayOutputStream(); out.writeBytes(lockingScript); out.write(0xc0);
        if (bytes.length > 75) out.write(76);
        out.write(bytes.length); out.writeBytes(bytes); out.write(0x75); return out.toByteArray();
    }

    private static boolean p2pkh(byte[] script) {
        return script != null && script.length == 25 && (script[0] & 255) == 0x76 && (script[1] & 255) == 0xa9
                && script[2] == 20 && (script[23] & 255) == 0x88 && (script[24] & 255) == 0xac;
    }
    private static String readName(BitcoinReader in) {
        long length = in.readCompactSize();
        if (length > 40) throw new IllegalArgumentException("RVN asset name byte length");
        return new String(in.readBytes((int) length), StandardCharsets.ISO_8859_1);
    }
    private static void writeName(ByteArrayOutputStream out, String name) {
        Objects.requireNonNull(name);
        if (name.length() > 40 || name.chars().anyMatch(c -> c > 255)) throw new IllegalArgumentException("RVN asset name bytes");
        byte[] bytes = name.getBytes(StandardCharsets.ISO_8859_1); out.write(bytes.length); out.writeBytes(bytes);
    }
    private static HashData readHash(BitcoinReader in) {
        int marker = in.readUnsignedByte(); long size = in.readCompactSize();
        if (size != 32) throw new IllegalArgumentException("RVN metadata digest size");
        return new HashData(marker, HexFormat.of().formatHex(in.readBytes(32)));
    }
    private static void writeHash(ByteArrayOutputStream out, HashData hash) {
        if (hash != null) { out.write(hash.marker()); out.write(32); out.writeBytes(HexFormat.of().parseHex(hash.digestHex())); }
    }
    private static boolean readBoolean(BitcoinReader in) {
        int value = in.readUnsignedByte();
        if (value > 1) throw new IllegalArgumentException("RVN asset boolean byte");
        return value != 0;
    }
    private static void writeLong(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 8; i++) out.write((int) (value >>> (8 * i)) & 255);
    }
    private static void writeUnits(ByteArrayOutputStream out, int units) {
        if (units < -128 || units > 127) throw new IllegalArgumentException("RVN units byte");
        out.write(units & 255);
    }
}
