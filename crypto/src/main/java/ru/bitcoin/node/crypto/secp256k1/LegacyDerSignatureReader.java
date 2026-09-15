package ru.bitcoin.node.crypto.secp256k1;

import java.math.BigInteger;
import java.util.Arrays;

/** Historical verification parsing, matching secp256k1's lax_der_parsing semantics.
 * Strict DER requirements are enforced separately by script flags (BIP66).
 */
public final class LegacyDerSignatureReader {
    private final byte[] bytes;
    private int position;
    private LegacyDerSignatureReader(byte[] bytes) { this.bytes = bytes; }

    public static EcdsaSignature parse(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("Missing signature");
        var reader = new LegacyDerSignatureReader(bytes);
        if (reader.read() != 0x30) throw new IllegalArgumentException("Missing DER sequence");
        int sequenceLength = reader.read();
        if ((sequenceLength & 0x80) != 0) reader.skip(sequenceLength & 0x7f);
        // Historical parser ignores the value of the sequence length and trailing bytes.
        return new EcdsaSignature(reader.integer(), reader.integer());
    }
    private BigInteger integer() {
        if (read() != 2) throw new IllegalArgumentException("Missing DER integer");
        long length = read();
        if ((length & 0x80) != 0) {
            int count = (int) length & 0x7f;
            if (count > bytes.length - position) throw new IllegalArgumentException("Truncated DER length");
            while (count > 0 && bytes[position] == 0) { position++; count--; }
            if (count > 4) throw new IllegalArgumentException("DER integer length overflow");
            length = 0;
            while (count-- > 0) length = (length << 8) | read();
        }
        if (length > bytes.length - position) throw new IllegalArgumentException("Truncated DER integer");
        int end = position + (int) length;
        while (position < end && bytes[position] == 0) position++;
        if (end - position > 32) throw new IllegalArgumentException("DER scalar overflow");
        byte[] value = Arrays.copyOfRange(bytes, position, end);
        position = end;
        return value.length == 0 ? BigInteger.ZERO : new BigInteger(1, value);
    }
    private int read() {
        if (position >= bytes.length) throw new IllegalArgumentException("Truncated DER signature");
        return bytes[position++] & 0xff;
    }
    private void skip(int count) {
        if (count > bytes.length - position) throw new IllegalArgumentException("Truncated DER sequence length");
        position += count;
    }
}
