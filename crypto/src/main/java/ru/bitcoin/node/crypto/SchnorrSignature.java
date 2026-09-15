package ru.bitcoin.node.crypto;

import org.bouncycastle.math.ec.ECPoint;
import ru.bitcoin.node.crypto.hash.TaggedHash;
import ru.bitcoin.node.crypto.secp256k1.Secp256k1;
import java.math.BigInteger;
import java.util.Arrays;

/** BIP340 verification. No private keys are needed by the consensus verifier. */
public final class SchnorrSignature {
    private SchnorrSignature() { }

    public static ECPoint liftX(byte[] key) {
        if (key.length != 32) throw new IllegalArgumentException("Expected 32-byte x-only key");
        byte[] compressed = new byte[33];
        compressed[0] = 2;
        System.arraycopy(key, 0, compressed, 1, 32);
        return Secp256k1.DOMAIN.getCurve().decodePoint(compressed).normalize();
    }

    public static boolean verify(byte[] message, byte[] key, byte[] signature) {
        if (message == null || key == null || signature == null || key.length != 32 || signature.length != 64) return false;
        try {
            ECPoint point = liftX(key);
            BigInteger r = new BigInteger(1, Arrays.copyOfRange(signature, 0, 32));
            BigInteger s = new BigInteger(1, Arrays.copyOfRange(signature, 32, 64));
            if (r.compareTo(Secp256k1.DOMAIN.getCurve().getField().getCharacteristic()) >= 0
                    || s.compareTo(Secp256k1.N) >= 0) return false;
            BigInteger challenge = new BigInteger(1, TaggedHash.hash("BIP0340/challenge",
                    Arrays.copyOfRange(signature, 0, 32), key, message)).mod(Secp256k1.N);
            ECPoint result = Secp256k1.DOMAIN.getG().multiply(s).subtract(point.multiply(challenge)).normalize();
            return !result.isInfinity() && !result.getAffineYCoord().toBigInteger().testBit(0)
                    && result.getAffineXCoord().toBigInteger().equals(r);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
