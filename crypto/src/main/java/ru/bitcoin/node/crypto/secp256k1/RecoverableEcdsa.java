package ru.bitcoin.node.crypto.secp256k1;

import org.bouncycastle.math.ec.ECAlgorithms;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Optional;

/** Bitcoin's 65-byte compact ECDSA signatures, with compressed/uncompressed recovery. */
public final class RecoverableEcdsa {
    private RecoverableEcdsa() { }

    public static byte[] sign(byte[] digest, PrivateKey privateKey, boolean compressed) {
        var signature = Secp256k1.sign(digest, privateKey);
        BigInteger inverseS = signature.s().modInverse(Secp256k1.N);
        var publicKey = Secp256k1.publicKey(privateKey);
        var point = ECAlgorithms.sumOfTwoMultiplies(Secp256k1.DOMAIN.getG(), new BigInteger(1, digest).multiply(inverseS).mod(Secp256k1.N),
                publicKey.point(), signature.r().multiply(inverseS).mod(Secp256k1.N)).normalize();
        int recovery = (point.getAffineYCoord().toBigInteger().testBit(0) ? 1 : 0)
                | (point.getAffineXCoord().toBigInteger().compareTo(Secp256k1.N) >= 0 ? 2 : 0);
        byte[] result = new byte[65]; result[0] = (byte) (27 + recovery + (compressed ? 4 : 0));
        fixed(signature.r(), result, 1); fixed(signature.s(), result, 33);
        return result;
    }

    public static Optional<byte[]> recover(byte[] digest, byte[] signature) {
        if (digest == null || digest.length != 32) throw new IllegalArgumentException("Digest must contain 32 bytes");
        if (signature == null || signature.length != 65) return Optional.empty();
        int header = signature[0] & 255;
        // Core interprets the low three bits of (header - 27), including aliases
        // outside the range emitted by SignCompact; it does not bound the header.
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(signature, 1, 33)), s = new BigInteger(1, Arrays.copyOfRange(signature, 33, 65));
        if (!Secp256k1.isValidPrivateKey(r) || !Secp256k1.isValidPrivateKey(s)) return Optional.empty();
        int recovery = (header - 27) & 3;
        BigInteger x = r.add((recovery & 2) == 0 ? BigInteger.ZERO : Secp256k1.N);
        if (x.compareTo(Secp256k1.DOMAIN.getCurve().getField().getCharacteristic()) >= 0) return Optional.empty();
        byte[] encoded = new byte[33]; encoded[0] = (byte) (2 + (recovery & 1)); fixed(x, encoded, 1);
        try {
            var point = Secp256k1.DOMAIN.getCurve().decodePoint(encoded);
            BigInteger inverseR = r.modInverse(Secp256k1.N), e = new BigInteger(1, digest);
            var publicPoint = ECAlgorithms.sumOfTwoMultiplies(Secp256k1.DOMAIN.getG(), e.negate().multiply(inverseR).mod(Secp256k1.N),
                    point, s.multiply(inverseR).mod(Secp256k1.N)).normalize();
            if (publicPoint.isInfinity()) return Optional.empty();
            return Optional.of(publicPoint.getEncoded(((header - 27) & 4) != 0));
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    private static void fixed(BigInteger integer, byte[] destination, int offset) {
        byte[] bytes = integer.toByteArray(); int count = Math.min(32, bytes.length);
        System.arraycopy(bytes, bytes.length - count, destination, offset + 32 - count, count);
    }
}
