package ru.bitcoin.node.crypto.transport;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * Ephemeral BIP324 key exchange state. One instance is used for one connection only.
 */
public final class Bip324KeyExchange {
    private final boolean initiating;
    private final Bip324ElligatorSwift.KeyPair local;
    private final byte[] networkMagic;
    private boolean completed;

    public Bip324KeyExchange(boolean initiating, byte[] networkMagic, SecureRandom random) {
        this.initiating = initiating;
        Objects.requireNonNull(networkMagic, "networkMagic");
        if (networkMagic.length != 4) throw new IllegalArgumentException("networkMagic must contain exactly 4 bytes");
        this.networkMagic = networkMagic.clone();
        this.local = Bip324ElligatorSwift.create(Objects.requireNonNull(random, "random"));
    }

    public byte[] publicKey() {
        return local.publicKey();
    }

    public synchronized Result complete(byte[] remotePublicKey) {
        if (completed) throw new IllegalStateException("BIP324 key exchange has already completed");
        byte[] secret = Bip324ElligatorSwift.ecdhSecret(local.privateKey(), remotePublicKey, local.publicKey(), initiating);
        Bip324KeyMaterial material = Bip324KeyMaterial.derive(secret, networkMagic, initiating);
        java.util.Arrays.fill(secret, (byte) 0);
        completed = true;
        return new Result(material, new Bip324PacketCipher(material));
    }

    public record Result(Bip324KeyMaterial keyMaterial, Bip324PacketCipher packetCipher) {
        public Result {
            Objects.requireNonNull(keyMaterial, "keyMaterial");
            Objects.requireNonNull(packetCipher, "packetCipher");
        }
    }
}
