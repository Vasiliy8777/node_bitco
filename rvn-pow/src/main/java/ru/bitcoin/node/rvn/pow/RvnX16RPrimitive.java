package ru.bitcoin.node.rvn.pow;

/**
 * One exact 512-bit cryptographic primitive as implemented in Ravencoin Core.
 */
@FunctionalInterface
public interface RvnX16RPrimitive {
    /**
     * The first round accepts 80 bytes, subsequent rounds accept 64 bytes.
     */
    byte[] digest(byte[] input) throws RvnPowException;
}
