package ru.bitcoin.node.app.rpc;

import org.bouncycastle.math.ec.ECPoint;
import ru.bitcoin.node.crypto.hash.TaggedHash;
import ru.bitcoin.node.crypto.secp256k1.Secp256k1;
import java.math.BigInteger;
import java.io.ByteArrayOutputStream;
import java.util.*;

/** BIP327 public-data aggregation, including verification of every participant. */
final class MuSig2Aggregation {
    private static final BigInteger N = Secp256k1.DOMAIN.getN();
    private static final ECPoint G = Secp256k1.DOMAIN.getG();
    record Tweak(byte[] scalar, boolean xonly) { }

    static byte[] publicKey(List<byte[]> keys) {
        if(keys.isEmpty())return null;
        try {
            byte[] second=new byte[33];for(byte[] key:keys)if(!Arrays.equals(key,keys.getFirst())){second=key;break;}
            var serialized=new ByteArrayOutputStream();keys.forEach(serialized::writeBytes);
            byte[] listHash=TaggedHash.hash("KeyAgg list",serialized.toByteArray());
            ECPoint aggregate=G.getCurve().getInfinity();
            for(byte[] key:keys) {
                BigInteger coefficient=Arrays.equals(key,second)?BigInteger.ONE:scalar(TaggedHash.hash("KeyAgg coefficient",listHash,key)).mod(N);
                aggregate=aggregate.add(point(key).multiply(coefficient));
            }
            return aggregate.isInfinity()?null:aggregate.normalize().getEncoded(true);
        } catch(IllegalArgumentException invalid) { return null; }
    }

    static byte[] aggregate(List<byte[]> keys, byte[] expectedAggregate, List<Tweak> tweaks,
                            byte[] message, List<byte[]> nonces, List<byte[]> partials) {
        if (keys.isEmpty() || keys.size() != nonces.size() || keys.size() != partials.size()) return null;
        try {
            byte[] second = new byte[33];
            for (byte[] key : keys) if (!Arrays.equals(key,keys.getFirst())) { second = key; break; }
            var serialized = new ByteArrayOutputStream(); keys.forEach(serialized::writeBytes);
            byte[] listHash = TaggedHash.hash("KeyAgg list",serialized.toByteArray());
            var coefficients = new ArrayList<BigInteger>();
            var points = new ArrayList<ECPoint>();
            ECPoint q = G.getCurve().getInfinity();
            for (byte[] key : keys) {
                ECPoint point = point(key); points.add(point);
                BigInteger coefficient = Arrays.equals(key,second) ? BigInteger.ONE
                        : scalar(TaggedHash.hash("KeyAgg coefficient",listHash,key)).mod(N);
                coefficients.add(coefficient); q = q.add(point.multiply(coefficient));
            }
            q = q.normalize();
            if (q.isInfinity() || !Arrays.equals(q.getEncoded(true),expectedAggregate)) return null;
            BigInteger gacc = BigInteger.ONE, tacc = BigInteger.ZERO;
            for (Tweak tweak : tweaks) {
                BigInteger t = scalar(tweak.scalar); if (t.compareTo(N) >= 0) return null;
                if (tweak.xonly && odd(q)) { q = q.negate(); gacc = gacc.negate(); tacc = tacc.negate(); }
                q = q.add(G.multiply(t)).normalize(); tacc = tacc.add(t).mod(N);
                if (q.isInfinity()) return null;
            }
            ECPoint r1 = G.getCurve().getInfinity(), r2 = r1;
            var noncePoints = new ArrayList<ECPoint[]>();
            for (byte[] nonce : nonces) {
                if (nonce.length != 66) return null;
                ECPoint a = point(Arrays.copyOfRange(nonce,0,33)), b = point(Arrays.copyOfRange(nonce,33,66));
                noncePoints.add(new ECPoint[]{a,b}); r1 = r1.add(a); r2 = r2.add(b);
            }
            byte[] aggregateNonce = join(extended(r1),extended(r2));
            BigInteger b = scalar(TaggedHash.hash("MuSig/noncecoef",aggregateNonce,x(q),message)).mod(N);
            ECPoint r = r1.add(r2.multiply(b)).normalize(); if (r.isInfinity()) r = G;
            BigInteger e = scalar(TaggedHash.hash("BIP0340/challenge",x(r),x(q),message)).mod(N);
            BigInteger keyFactor = odd(q) ? gacc.negate() : gacc;
            BigInteger sum = BigInteger.ZERO;
            for (int index=0;index<keys.size();index++) {
                if (partials.get(index).length != 32) return null;
                BigInteger s = scalar(partials.get(index)); if (s.compareTo(N) >= 0) return null;
                ECPoint[] nonce = noncePoints.get(index);
                ECPoint effective = nonce[0].add(nonce[1].multiply(b));
                if (odd(r)) effective = effective.negate();
                ECPoint right = effective.add(points.get(index).multiply(e.multiply(coefficients.get(index)).multiply(keyFactor).mod(N)));
                if (!G.multiply(s).normalize().equals(right.normalize())) return null;
                sum = sum.add(s);
            }
            BigInteger correction = e.multiply(tacc); if (odd(q)) correction = correction.negate();
            return join(x(r),bytes32(sum.add(correction).mod(N)));
        } catch (IllegalArgumentException invalid) { return null; }
    }

    private static ECPoint point(byte[] encoded) {
        if (encoded.length != 33 || encoded[0] != 2 && encoded[0] != 3) throw new IllegalArgumentException("Invalid compressed point");
        ECPoint result = G.getCurve().decodePoint(encoded).normalize();
        if (result.isInfinity() || !result.isValid()) throw new IllegalArgumentException("Invalid point");
        return result;
    }
    private static boolean odd(ECPoint point) { return point.normalize().getAffineYCoord().toBigInteger().testBit(0); }
    private static byte[] x(ECPoint point) { return point.normalize().getAffineXCoord().getEncoded(); }
    private static byte[] extended(ECPoint point) { return point.isInfinity() ? new byte[33] : point.normalize().getEncoded(true); }
    private static BigInteger scalar(byte[] bytes) { return new BigInteger(1,bytes); }
    private static byte[] bytes32(BigInteger value) {
        byte[] bytes = value.toByteArray(), result = new byte[32];
        System.arraycopy(bytes,Math.max(0,bytes.length-32),result,Math.max(0,32-bytes.length),Math.min(32,bytes.length)); return result;
    }
    private static byte[] join(byte[] first,byte[] second) {
        byte[] result = Arrays.copyOf(first,first.length+second.length); System.arraycopy(second,0,result,first.length,second.length); return result;
    }
}
