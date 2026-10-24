package ru.bitcoin.node.rvn.chain.assets;

import java.util.*;

/** Exact qualifier assignment, address/global freeze or restricted verifier key.
 * Address identity is the 20-byte destination key hash in hex, not a display address. */
public record RvnAssetRuleKey(Kind kind, String name, String addressHash) {
    public enum Kind { QUALIFIER, ADDRESS_FREEZE, GLOBAL_FREEZE, VERIFIER }
    public RvnAssetRuleKey {
        Objects.requireNonNull(kind); Objects.requireNonNull(addressHash);
        // CheckTransaction permits historical coinbase global commands whose
        // name is not a restricted asset. AddCoins persists those opaque keys.
        if (kind == Kind.GLOBAL_FREEZE) {
            if (name == null || name.isEmpty() || name.length() > 40 || name.chars().anyMatch(c -> c > 255))
                throw new IllegalArgumentException("RVN global rule name encoding/size");
        }
        var type = kind == Kind.GLOBAL_FREEZE ? null : RvnAssetNames.requireValid(name);
        boolean qualifier = type == RvnAssetNames.Type.QUALIFIER || type == RvnAssetNames.Type.SUB_QUALIFIER;
        if (kind != Kind.GLOBAL_FREEZE && (kind == Kind.QUALIFIER ? !qualifier : type != RvnAssetNames.Type.RESTRICTED))
            throw new IllegalArgumentException("RVN rule family/name mismatch");
        boolean address = kind == Kind.QUALIFIER || kind == Kind.ADDRESS_FREEZE;
        if (address ? !addressHash.matches("[0-9a-f]{40}") : !addressHash.isEmpty())
            throw new IllegalArgumentException("RVN rule address identity");
    }
    public void requireValue(String value) {
        if (kind == Kind.VERIFIER) {
            if (value == null || value.isEmpty() || value.length() > 80 || value.chars().anyMatch(c -> c > 127))
                throw new IllegalArgumentException("Invalid RVN stored verifier size/encoding");
        } else if (!"1".equals(value)) throw new IllegalArgumentException("RVN boolean rule must be present as 1 or absent");
    }
}
