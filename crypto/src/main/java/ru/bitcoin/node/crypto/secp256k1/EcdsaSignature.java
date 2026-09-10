package ru.bitcoin.node.crypto.secp256k1;

import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERSequence;

import java.io.IOException;
import java.math.BigInteger;

public record EcdsaSignature(
        BigInteger r,
        BigInteger s
) {

    public EcdsaSignature {
        if (r == null || s == null) {
            throw new IllegalArgumentException(
                    "r and s must not be null"
            );
        }

        if (r.signum() <= 0
                || r.compareTo(Secp256k1.N) >= 0) {

            throw new IllegalArgumentException(
                    "r must satisfy 1 <= r < n"
            );
        }

        if (s.signum() <= 0
                || s.compareTo(Secp256k1.N) >= 0) {

            throw new IllegalArgumentException(
                    "s must satisfy 1 <= s < n"
            );
        }
    }

    public EcdsaSignature toLowS() {
        if (s.compareTo(Secp256k1.HALF_N) <= 0) {
            return this;
        }

        return new EcdsaSignature(
                r,
                Secp256k1.N.subtract(s)
        );
    }

    public boolean isLowS() {
        return s.compareTo(
                Secp256k1.HALF_N
        ) <= 0;
    }

    public byte[] toDer() {
        try {
            DERSequence sequence =
                    new DERSequence(
                            new ASN1Integer[]{
                                    new ASN1Integer(r),
                                    new ASN1Integer(s)
                            }
                    );

            return sequence.getEncoded();

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to DER encode ECDSA signature",
                    e
            );
        }
    }

    public static EcdsaSignature fromDer(
            byte[] der
    ) {
        if (der == null) {
            throw new IllegalArgumentException(
                    "der must not be null"
            );
        }

        try {
            ASN1Primitive primitive =
                    ASN1Primitive.fromByteArray(der);

            ASN1Sequence sequence =
                    ASN1Sequence.getInstance(primitive);

            if (sequence.size() != 2) {
                throw new IllegalArgumentException(
                        "ECDSA DER signature must contain r and s"
                );
            }

            BigInteger r =
                    ASN1Integer
                            .getInstance(sequence.getObjectAt(0))
                            .getPositiveValue();

            BigInteger s =
                    ASN1Integer
                            .getInstance(sequence.getObjectAt(1))
                            .getPositiveValue();

            return new EcdsaSignature(r, s);

        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException(
                    "Invalid DER ECDSA signature",
                    e
            );
        }
    }
}
