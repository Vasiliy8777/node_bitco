package ru.bitcoin.node.crypto.transport;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class Bip324PacketCipherTest {
    private static final byte[] MAGIC = {(byte) 0xf9, (byte) 0xbe, (byte) 0xb4, (byte) 0xd9};

    @Test
    void initiatorAndResponderDeriveOppositeDirectionsAndSameSession() {
        byte[] secret = sequence(32);
        var initiator = Bip324KeyMaterial.derive(secret, MAGIC, true);
        var responder = Bip324KeyMaterial.derive(secret, MAGIC, false);
        assertArrayEquals(initiator.sessionId(), responder.sessionId());
        assertArrayEquals(initiator.sendLengthKey(), responder.receiveLengthKey());
        assertArrayEquals(initiator.sendPayloadKey(), responder.receivePayloadKey());
        assertArrayEquals(initiator.sendGarbageTerminator(), responder.receiveGarbageTerminator());
    }

    @Test
    void packetsRoundTripAcrossRekeyBoundary() {
        var a = new Bip324PacketCipher(Bip324KeyMaterial.derive(sequence(32), MAGIC, true));
        var b = new Bip324PacketCipher(Bip324KeyMaterial.derive(sequence(32), MAGIC, false));
        for (int i = 0; i < 450; i++) {
            byte[] contents = new byte[(i % 97) + 1];
            Arrays.fill(contents, (byte) i);
            byte[] aad = i == 0 ? new byte[]{1, 2, 3, 4, 5} : new byte[0];
            byte[] packet = a.encrypt(contents, aad, i % 17 == 0);
            int length = b.decryptLength(Arrays.copyOfRange(packet, 0, 3));
            assertEquals(contents.length, length);
            var decoded = b.decryptPayload(Arrays.copyOfRange(packet, 3, packet.length), aad);
            assertArrayEquals(contents, decoded.contents());
            assertEquals(i % 17 == 0, decoded.ignore());
        }
    }

    @Test
    void authenticationFailureIsRejected() {
        var a = new Bip324PacketCipher(Bip324KeyMaterial.derive(sequence(32), MAGIC, true));
        var b = new Bip324PacketCipher(Bip324KeyMaterial.derive(sequence(32), MAGIC, false));
        byte[] packet = a.encrypt(new byte[]{9, 8, 7}, new byte[0], false);
        b.decryptLength(Arrays.copyOfRange(packet, 0, 3));
        packet[packet.length - 1] ^= 1;
        assertThrows(SecurityException.class, () -> b.decryptPayload(Arrays.copyOfRange(packet, 3, packet.length), new byte[0]));
    }

    private static byte[] sequence(int n) {
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) i;
        return out;
    }
}
