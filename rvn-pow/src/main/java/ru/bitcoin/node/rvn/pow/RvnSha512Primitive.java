package ru.bitcoin.node.rvn.pow;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-512 primitive used in X16R round 15 (not a replacement for other rounds).
 */
public final class RvnSha512Primitive implements RvnX16RPrimitive {
    @Override
    public byte[] digest(byte[] input) throws RvnPowException {
        if (input == null) throw new RvnPowException("Null SHA512 input");
        try {
            return MessageDigest.getInstance("SHA-512").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new RvnPowException("SHA-512 unavailable", e);
        }
    }
}
