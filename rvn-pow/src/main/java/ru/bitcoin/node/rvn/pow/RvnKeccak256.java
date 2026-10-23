package ru.bitcoin.node.rvn.pow;

/**
 * Real legacy Keccak-256, with original Keccak domain separation.
 */
public final class RvnKeccak256 {
    private RvnKeccak256() {
    }

    public static byte[] digest(byte[] input) {
        return RvnKeccakSponge.digest256(input);
    }
}
