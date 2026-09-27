package ru.bitcoin.node.crypto.transport;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.crypto.secp256k1.PrivateKey;
import ru.bitcoin.node.crypto.secp256k1.Secp256k1;

import java.math.BigInteger;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

class Bip324ElligatorSwiftTest {
    @Test
    void arbitraryEncodingAlwaysDecodesToValidCurveX() {
        byte[] encoded = new byte[64];
        for (int i = 0; i < encoded.length; i++) encoded[i] = (byte) (i * 37 + 11);
        byte[] x = Bip324ElligatorSwift.decodeX(encoded);
        assertEquals(32, x.length);
        BigInteger value = new BigInteger(1, x);
        assertDoesNotThrow(() -> Secp256k1.DOMAIN.getCurve().decodePoint(concat((byte) 0x02, x)));
        assertTrue(value.signum() >= 0);
    }

    @Test
    void generatedEncodingRoundTripsPublicX() throws Exception {
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        random.setSeed(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        var pair = Bip324ElligatorSwift.create(random);
        byte[] expected = java.util.Arrays.copyOfRange(Secp256k1.publicKey(pair.privateKey()).compressed(), 1, 33);
        assertArrayEquals(expected, Bip324ElligatorSwift.decodeX(pair.publicKey()));
    }

    @Test
    void taggedXOnlyEcdhIsSymmetricAcrossRoles() throws Exception {
        SecureRandom aRandom = SecureRandom.getInstance("SHA1PRNG");
        aRandom.setSeed(new byte[]{10, 11, 12});
        SecureRandom bRandom = SecureRandom.getInstance("SHA1PRNG");
        bRandom.setSeed(new byte[]{20, 21, 22});
        var initiator = Bip324ElligatorSwift.create(aRandom);
        var responder = Bip324ElligatorSwift.create(bRandom);
        byte[] a = Bip324ElligatorSwift.ecdhSecret(initiator.privateKey(), responder.publicKey(), initiator.publicKey(), true);
        byte[] b = Bip324ElligatorSwift.ecdhSecret(responder.privateKey(), initiator.publicKey(), responder.publicKey(), false);
        assertArrayEquals(a, b);
        assertEquals(32, a.length);
    }

    @Test
    void inverseProducesEncodingForRequestedX() throws Exception {
        PrivateKey key = new PrivateKey(BigInteger.valueOf(123456789));
        BigInteger x = new BigInteger(1, java.util.Arrays.copyOfRange(Secp256k1.publicKey(key).compressed(), 1, 33));
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        random.setSeed(new byte[]{99, 88, 77});
        byte[] encoded = Bip324ElligatorSwift.encodeX(x, random);
        assertArrayEquals(to32(x), Bip324ElligatorSwift.decodeX(encoded));
    }

    private static byte[] concat(byte prefix, byte[] body) {
        byte[] out = new byte[body.length + 1];
        out[0] = prefix;
        System.arraycopy(body, 0, out, 1, body.length);
        return out;
    }

    private static byte[] to32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int src = raw.length > 32 ? raw.length - 32 : 0;
        int len = Math.min(32, raw.length);
        System.arraycopy(raw, src, out, 32 - len, len);
        return out;
    }
}
