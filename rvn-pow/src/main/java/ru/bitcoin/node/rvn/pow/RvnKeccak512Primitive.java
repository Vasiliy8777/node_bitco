package ru.bitcoin.node.rvn.pow;

/**
 * Ravencoin Keccak-512 round primitive (legacy Keccak, not NIST SHA3).
 */
public final class RvnKeccak512Primitive implements RvnX16RPrimitive {
    @Override
    public byte[] digest(byte[] input) throws RvnPowException {
        if (input == null) throw new RvnPowException("Null Keccak input");
        return RvnKeccak512.digest(input);
    }
}
