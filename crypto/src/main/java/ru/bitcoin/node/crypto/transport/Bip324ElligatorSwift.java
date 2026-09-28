package ru.bitcoin.node.crypto.transport;

import org.bouncycastle.math.ec.ECPoint;
import ru.bitcoin.node.crypto.secp256k1.PrivateKey;
import ru.bitcoin.node.crypto.secp256k1.Secp256k1;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/** BIP324 ElligatorSwift encoding and tagged X-only ECDH for secp256k1. */
public final class Bip324ElligatorSwift {
    public static final int ENCODED_LENGTH = 64;
    private static final BigInteger P = Secp256k1.DOMAIN.getCurve().getField().getCharacteristic();
    private static final BigInteger TWO = BigInteger.TWO;
    private static final BigInteger THREE = BigInteger.valueOf(3);
    private static final BigInteger FOUR = BigInteger.valueOf(4);
    private static final BigInteger SEVEN = BigInteger.valueOf(7);
    private static final BigInteger SQRT_EXPONENT = P.add(BigInteger.ONE).shiftRight(2);
    private static final BigInteger MINUS_3_SQRT = sqrt(mod(THREE.negate()));
    private static final byte[] ECDH_TAG = "bip324_ellswift_xonly_ecdh".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private Bip324ElligatorSwift() { }

    public record KeyPair(PrivateKey privateKey, byte[] publicKey) {
        public KeyPair {
            Objects.requireNonNull(privateKey, "privateKey");
            publicKey = requireEncoding(publicKey).clone();
        }
        @Override public byte[] publicKey() { return publicKey.clone(); }
    }

    public static KeyPair create(SecureRandom random) {
        Objects.requireNonNull(random, "random");
        PrivateKey privateKey;
        do {
            byte[] candidate = new byte[32];
            random.nextBytes(candidate);
            BigInteger d = new BigInteger(1, candidate);
            if (Secp256k1.isValidPrivateKey(d)) {
                privateKey = new PrivateKey(d);
                break;
            }
        } while (true);
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(Secp256k1.publicKey(privateKey).compressed(), 1, 33));
        return new KeyPair(privateKey, encodeX(x, random));
    }

    public static byte[] encodeX(BigInteger x, SecureRandom random) {
        Objects.requireNonNull(x, "x");
        Objects.requireNonNull(random, "random");
        x = mod(x);
        if (!isValidX(x)) throw new IllegalArgumentException("x is not a secp256k1 curve X coordinate");
        for (;;) {
            BigInteger u;
            do {
                byte[] bytes = new byte[32];
                random.nextBytes(bytes);
                u = new BigInteger(1, bytes);
            } while (u.signum() == 0 || u.compareTo(P) >= 0);
            int start = random.nextInt(8);
            for (int i = 0; i < 8; i++) {
                BigInteger t = xSwiftEcInv(x, u, (start + i) & 7);
                if (t != null) return concat32(u, t);
            }
        }
    }

    public static byte[] decodeX(byte[] encoding) {
        requireEncoding(encoding);
        BigInteger u = new BigInteger(1, Arrays.copyOfRange(encoding, 0, 32)).mod(P);
        BigInteger t = new BigInteger(1, Arrays.copyOfRange(encoding, 32, 64)).mod(P);
        return to32(xSwiftEc(u, t));
    }

    public static byte[] ecdhSecret(PrivateKey ours, byte[] theirs, byte[] oursEncoded, boolean initiating) {
        Objects.requireNonNull(ours, "ours");
        requireEncoding(theirs);
        requireEncoding(oursEncoded);
        BigInteger x = new BigInteger(1, decodeX(theirs));
        ECPoint point = liftXEven(x).multiply(ours.value()).normalize();
        byte[] sharedX = to32(point.getAffineXCoord().toBigInteger());
        byte[] transcript = initiating
                ? concat(oursEncoded, theirs, sharedX)
                : concat(theirs, oursEncoded, sharedX);
        return taggedHash(ECDH_TAG, transcript);
    }

    static BigInteger xSwiftEc(BigInteger u, BigInteger t) {
        u = mod(u); t = mod(t);
        if (u.signum() == 0) u = BigInteger.ONE;
        if (t.signum() == 0) t = BigInteger.ONE;
        if (mod(u.modPow(THREE, P).add(t.multiply(t)).add(SEVEN)).signum() == 0) t = mod(TWO.multiply(t));
        BigInteger X = div(mod(u.modPow(THREE, P).add(SEVEN).subtract(t.multiply(t))), TWO.multiply(t));
        BigInteger Y = div(X.add(t), MINUS_3_SQRT.multiply(u));
        BigInteger[] candidates = {
                mod(u.add(FOUR.multiply(Y).multiply(Y))),
                div(mod(X.negate().multiply(inv(Y)).subtract(u)), TWO),
                div(mod(X.multiply(inv(Y)).subtract(u)), TWO)
        };
        for (BigInteger x : candidates) if (isValidX(x)) return x;
        throw new IllegalStateException("XSwiftEC failed to produce a curve X coordinate");
    }

    static BigInteger xSwiftEcInv(BigInteger x, BigInteger u, int caze) {
        x = mod(x); u = mod(u);
        BigInteger v, s;
        if ((caze & 2) == 0) {
            if (isValidX(mod(x.negate().subtract(u)))) return null;
            v = x;
            BigInteger denominator = mod(u.multiply(u).add(u.multiply(v)).add(v.multiply(v)));
            if (denominator.signum() == 0) return null;
            s = div(mod(u.modPow(THREE, P).add(SEVEN).negate()), denominator);
        } else {
            s = mod(x.subtract(u));
            if (s.signum() == 0) return null;
            BigInteger inside = mod(s.negate().multiply(
                    FOUR.multiply(u.modPow(THREE, P).add(SEVEN)).add(THREE.multiply(s).multiply(u).multiply(u))));
            BigInteger r = sqrtOrNull(inside);
            if (r == null || ((caze & 1) != 0 && r.signum() == 0)) return null;
            v = div(mod(u.negate().add(div(r, s))), TWO);
        }
        BigInteger w = sqrtOrNull(s);
        if (w == null) return null;
        BigInteger left = div(u.multiply(BigInteger.ONE.subtract(MINUS_3_SQRT)), TWO).add(v);
        BigInteger right = div(u.multiply(BigInteger.ONE.add(MINUS_3_SQRT)), TWO).add(v);
        return switch (caze & 5) {
            case 0 -> mod(w.negate().multiply(left));
            case 1 -> mod(w.multiply(right));
            case 4 -> mod(w.multiply(left));
            case 5 -> mod(w.negate().multiply(right));
            default -> throw new IllegalStateException("unreachable case");
        };
    }

    private static ECPoint liftXEven(BigInteger x) {
        BigInteger y = sqrt(mod(x.modPow(THREE, P).add(SEVEN)));
        if (y.testBit(0)) y = P.subtract(y);
        return Secp256k1.DOMAIN.getCurve().createPoint(x, y).normalize();
    }

    private static boolean isValidX(BigInteger x) { return sqrtOrNull(mod(x.modPow(THREE, P).add(SEVEN))) != null; }
    private static BigInteger sqrt(BigInteger a) { BigInteger r = sqrtOrNull(a); if (r == null) throw new IllegalArgumentException("not a square"); return r; }
    private static BigInteger sqrtOrNull(BigInteger a) { a = mod(a); BigInteger r = a.modPow(SQRT_EXPONENT, P); return mod(r.multiply(r)).equals(a) ? r : null; }
    private static BigInteger inv(BigInteger a) { a = mod(a); if (a.signum() == 0) throw new ArithmeticException("division by zero"); return a.modInverse(P); }
    private static BigInteger div(BigInteger a, BigInteger b) { return mod(a).multiply(inv(b)).mod(P); }
    private static BigInteger mod(BigInteger a) { a = a.mod(P); return a.signum() < 0 ? a.add(P) : a; }

    private static byte[] taggedHash(byte[] tag, byte[] data) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] tagHash = sha.digest(tag);
            sha.reset(); sha.update(tagHash); sha.update(tagHash); sha.update(data);
            return sha.digest();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static byte[] requireEncoding(byte[] value) { if (value == null || value.length != ENCODED_LENGTH) throw new IllegalArgumentException("ElligatorSwift encoding must contain exactly 64 bytes"); return value; }
    private static byte[] concat32(BigInteger a, BigInteger b) { return concat(to32(a), to32(b)); }
    private static byte[] to32(BigInteger value) { byte[] raw = mod(value).toByteArray(); byte[] out = new byte[32]; int src = raw.length > 32 ? raw.length - 32 : 0; int len = Math.min(32, raw.length); System.arraycopy(raw, src, out, 32 - len, len); return out; }
    private static byte[] concat(byte[]... parts) { int n=0; for(byte[] p:parts)n+=p.length; byte[] out=new byte[n]; int at=0; for(byte[] p:parts){System.arraycopy(p,0,out,at,p.length);at+=p.length;} return out; }
}
