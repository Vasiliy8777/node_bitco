package ru.bitcoin.node.rvn.pow;

/**
 * Independent audited TIGER-192 implementation is required for X16RV2.
 */
@FunctionalInterface
public interface RvnX16Rv2Primitive {
    byte[] tiger192(byte[] input) throws RvnPowException;
}
