package ru.bitcoin.node.crypto.secp256k1;

import org.bouncycastle.math.ec.ECPoint;

import java.util.Arrays;

public final class PublicKey {

    private final ECPoint point;

    PublicKey(ECPoint point) {
        if (point == null) {
            throw new IllegalArgumentException(
                    "point must not be null"
            );
        }

        if (point.isInfinity()) {
            throw new IllegalArgumentException(
                    "Public key cannot be point at infinity"
            );
        }

        this.point = point.normalize();
    }

    public static PublicKey fromBytes(byte[] encoded) {
        if (encoded != null && encoded.length == 0) {
            throw new IllegalArgumentException("Public key encoding must not be empty");
        }
        if (encoded == null) {
            throw new IllegalArgumentException(
                    "encoded must not be null"
            );
        }

        try {
            ECPoint point =
                    Secp256k1.DOMAIN
                            .getCurve()
                            .decodePoint(encoded);

            return new PublicKey(point);

        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid secp256k1 public key",
                    e
            );
        }
    }

    public byte[] compressed() {
        return point.getEncoded(true);
    }

    public byte[] uncompressed() {
        return point.getEncoded(false);
    }

    ECPoint point() {
        return point;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }

        if (!(object instanceof PublicKey other)) {
            return false;
        }

        return point.equals(other.point);
    }

    @Override
    public int hashCode() {
        return point.hashCode();
    }

    @Override
    public String toString() {
        return Arrays.toString(compressed());
    }
}
