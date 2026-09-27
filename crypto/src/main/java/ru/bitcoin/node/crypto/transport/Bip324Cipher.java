package ru.bitcoin.node.crypto.transport;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.ChaCha7539Engine;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import java.util.Arrays;
import java.util.Objects;

/**
 * Forward-secure ChaCha20 and ChaCha20-Poly1305 wrappers specified by BIP324.
 */
final class Bip324Cipher {
    static final int REKEY_INTERVAL = 224;

    private Bip324Cipher() {
    }

    static final class LengthCipher {
        private byte[] key;
        private long blockCounter;
        private long chunkCounter;
        private byte[] buffered = new byte[0];

        LengthCipher(byte[] key) {
            this.key = key32(key);
        }

        byte[] crypt(byte[] chunk) {
            Objects.requireNonNull(chunk, "chunk");
            byte[] stream = keystream(chunk.length);
            byte[] out = new byte[chunk.length];
            for (int i = 0; i < chunk.length; i++) out[i] = (byte) (chunk[i] ^ stream[i]);
            if ((chunkCounter + 1) % REKEY_INTERVAL == 0) {
                key = keystream(32);
                blockCounter = 0;
            }
            chunkCounter++;
            return out;
        }

        private byte[] keystream(int count) {
            byte[] out = new byte[count];
            int offset = 0;
            while (offset < count) {
                if (buffered.length == 0) {
                    byte[] nonce = nonce(0, chunkCounter / REKEY_INTERVAL);
                    buffered = chachaBlock(key, nonce, blockCounter++);
                }
                int take = Math.min(buffered.length, count - offset);
                System.arraycopy(buffered, 0, out, offset, take);
                buffered = Arrays.copyOfRange(buffered, take, buffered.length);
                offset += take;
            }
            return out;
        }
    }

    static final class PayloadCipher {
        private byte[] key;
        private long packetCounter;

        PayloadCipher(byte[] key) {
            this.key = key32(key);
        }

        byte[] encrypt(byte[] aad, byte[] plaintext) {
            return crypt(aad, plaintext, false);
        }

        byte[] decrypt(byte[] aad, byte[] ciphertext) {
            return crypt(aad, ciphertext, true);
        }

        private byte[] crypt(byte[] aad, byte[] text, boolean decrypt) {
            Objects.requireNonNull(aad, "aad");
            Objects.requireNonNull(text, "text");
            byte[] nonce = nonce(packetCounter % REKEY_INTERVAL, packetCounter / REKEY_INTERVAL);
            byte[] result = aead(key, nonce, aad, text, decrypt);
            if (result == null) return null; // failed authentication must not advance/rekey state
            if ((packetCounter + 1) % REKEY_INTERVAL == 0) {
                byte[] rekeyNonce = nonce(0xffff_ffffL, packetCounter / REKEY_INTERVAL);
                key = Arrays.copyOf(aead(key, rekeyNonce, new byte[0], new byte[32], false), 32);
            }
            packetCounter++;
            return result;
        }
    }

    private static byte[] aead(byte[] key, byte[] nonce, byte[] aad, byte[] input, boolean decrypt) {
        ChaCha20Poly1305 cipher = new ChaCha20Poly1305();
        cipher.init(!decrypt, new AEADParameters(new KeyParameter(key), 128, nonce, aad));
        byte[] out = new byte[cipher.getOutputSize(input.length)];
        int written = cipher.processBytes(input, 0, input.length, out, 0);
        try {
            written += cipher.doFinal(out, written);
            return Arrays.copyOf(out, written);
        } catch (InvalidCipherTextException e) {
            if (decrypt) return null;
            throw new IllegalStateException("ChaCha20-Poly1305 encryption failed", e);
        }
    }

    private static byte[] chachaBlock(byte[] key, byte[] nonce, long blockCounter) {
        if (blockCounter > 0xffff_ffffL) throw new IllegalStateException("ChaCha20 block counter exhausted");
        ChaCha7539Engine engine = new ChaCha7539Engine();
        engine.init(true, new ParametersWithIV(new KeyParameter(key), nonce));
        if (blockCounter != 0) engine.skip(blockCounter * 64L);
        byte[] zero = new byte[64];
        byte[] out = new byte[64];
        engine.processBytes(zero, 0, 64, out, 0);
        return out;
    }

    private static byte[] nonce(long low32, long high64) {
        byte[] out = new byte[12];
        write32(out, 0, low32);
        write64(out, 4, high64);
        return out;
    }

    private static void write32(byte[] out, int offset, long value) {
        for (int i = 0; i < 4; i++) out[offset + i] = (byte) (value >>> (8 * i));
    }

    private static void write64(byte[] out, int offset, long value) {
        for (int i = 0; i < 8; i++) out[offset + i] = (byte) (value >>> (8 * i));
    }

    private static byte[] key32(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length != 32) throw new IllegalArgumentException("BIP324 cipher key must be 32 bytes");
        return key.clone();
    }
}
