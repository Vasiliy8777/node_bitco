package ru.bitcoin.node.crypto.secp256k1;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;

import java.math.BigInteger;

public final class Secp256k1 {

    private static final X9ECParameters PARAMETERS =
            CustomNamedCurves.getByName("secp256k1");

    public static final ECDomainParameters DOMAIN =
            new ECDomainParameters(
                    PARAMETERS.getCurve(),
                    PARAMETERS.getG(),
                    PARAMETERS.getN(),
                    PARAMETERS.getH()
            );

    /**
     * Порядок базовой точки G.
     *
     * Все приватные ключи и значения r,s находятся
     * в диапазоне относительно N.
     */
    public static final BigInteger N =
            PARAMETERS.getN();

    /**
     * N / 2.
     *
     * Используется для low-S normalization.
     */
    public static final BigInteger HALF_N =
            N.shiftRight(1);

    private Secp256k1() {
    }

    public static boolean isValidPrivateKey(BigInteger value) {
        return value != null
                && value.signum() > 0
                && value.compareTo(N) < 0;
    }
    public static PublicKey publicKey(
            PrivateKey privateKey
    ) {
        if (privateKey == null) {
            throw new IllegalArgumentException(
                    "privateKey must not be null"
            );
        }

        return new PublicKey(
                DOMAIN
                        .getG()
                        .multiply(privateKey.value())
                        .normalize()
        );
    }

    //ECDSA-подпись

    public static EcdsaSignature sign(
            byte[] digest,
            PrivateKey privateKey
    ) {
        requireDigest(digest);

        if (privateKey == null) {
            throw new IllegalArgumentException(
                    "privateKey must not be null"
            );
        }

        ECDSASigner signer =
                new ECDSASigner(
                        new HMacDSAKCalculator(
                                new SHA256Digest()
                        )
                );

        signer.init(
                true,
                new ECPrivateKeyParameters(
                        privateKey.value(),
                        DOMAIN
                )
        );

        BigInteger[] signature =
                signer.generateSignature(digest);

        return new EcdsaSignature(
                signature[0],
                signature[1]
        ).toLowS();
    }
    // проверка
    public static boolean verify(
            byte[] digest,
            EcdsaSignature signature,
            PublicKey publicKey
    ) {
        requireDigest(digest);

        if (signature == null) {
            throw new IllegalArgumentException(
                    "signature must not be null"
            );
        }

        if (publicKey == null) {
            throw new IllegalArgumentException(
                    "publicKey must not be null"
            );
        }

        ECDSASigner signer =
                new ECDSASigner();

        signer.init(
                false,
                new ECPublicKeyParameters(
                        publicKey.point(),
                        DOMAIN
                )
        );

        return signer.verifySignature(
                digest,
                signature.r(),
                signature.s()
        );
    }

    private static void requireDigest(
            byte[] digest
    ) {
        if (digest == null) {
            throw new IllegalArgumentException(
                    "digest must not be null"
            );
        }

        if (digest.length != 32) {
            throw new IllegalArgumentException(
                    "ECDSA digest must contain exactly 32 bytes"
            );
        }
    }
}
