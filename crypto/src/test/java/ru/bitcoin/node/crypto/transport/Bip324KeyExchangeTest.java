package ru.bitcoin.node.crypto.transport;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

class Bip324KeyExchangeTest {
    @Test
    void initiatorAndResponderDeriveSameSessionAndOppositeDirections() throws Exception {
        byte[] magic = {(byte) 0xf9, (byte) 0xbe, (byte) 0xb4, (byte) 0xd9};
        SecureRandom ar = SecureRandom.getInstance("SHA1PRNG");
        ar.setSeed(new byte[]{1, 3, 3, 7});
        SecureRandom br = SecureRandom.getInstance("SHA1PRNG");
        br.setSeed(new byte[]{4, 2, 4, 2});
        var a = new Bip324KeyExchange(true, magic, ar);
        var b = new Bip324KeyExchange(false, magic, br);
        var aResult = a.complete(b.publicKey());
        var bResult = b.complete(a.publicKey());
        assertArrayEquals(aResult.keyMaterial().sessionId(), bResult.keyMaterial().sessionId());
        assertArrayEquals(aResult.keyMaterial().sendLengthKey(), bResult.keyMaterial().receiveLengthKey());
        assertArrayEquals(aResult.keyMaterial().sendPayloadKey(), bResult.keyMaterial().receivePayloadKey());
        assertArrayEquals(aResult.keyMaterial().sendGarbageTerminator(), bResult.keyMaterial().receiveGarbageTerminator());
    }

    @Test
    void exchangeIsSingleUse() throws Exception {
        byte[] magic = {1, 2, 3, 4};
        SecureRandom ar = SecureRandom.getInstance("SHA1PRNG");
        ar.setSeed(new byte[]{1});
        SecureRandom br = SecureRandom.getInstance("SHA1PRNG");
        br.setSeed(new byte[]{2});
        var a = new Bip324KeyExchange(true, magic, ar);
        var b = new Bip324KeyExchange(false, magic, br);
        a.complete(b.publicKey());
        assertThrows(IllegalStateException.class, () -> a.complete(b.publicKey()));
    }
}
