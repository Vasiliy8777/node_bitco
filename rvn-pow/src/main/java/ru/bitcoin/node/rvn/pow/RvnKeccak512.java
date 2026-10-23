package ru.bitcoin.node.rvn.pow;

/**
 * Real legacy Keccak-512. Retained API delegates to shared sponge.
 */
public final class RvnKeccak512 {
    private RvnKeccak512() {
    }

    public static byte[] digest(byte[] input) {
        return RvnKeccakSponge.digest512(input);
    }
}
