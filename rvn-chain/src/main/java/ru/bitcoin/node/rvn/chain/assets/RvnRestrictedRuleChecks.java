package ru.bitcoin.node.rvn.chain.assets;

import java.util.*;
import java.util.function.Predicate;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetRuleKey.Kind.*;

/** Contextual rule transitions/checks using only a stable parent snapshot.
 * Transaction authorization, qualifier-tag burns and persistent commit are separate. */
public final class RvnRestrictedRuleChecks {
    private RvnRestrictedRuleChecks() {}

    public static Optional<RvnAssetChanges.RuleUpdate> toggle(RvnAssetRulesView parent, RvnAssetRuleKey key, int flag) {
        if (key.kind() == VERIFIER || (flag != 0 && flag != 1)) throw new SecurityException("RVN invalid rule flag/kind");
        var before = parent.findRule(key);
        // Core's qualifier transition checks use aggregate root/sub membership.
        boolean present = key.kind() == QUALIFIER ? parent.hasQualifier(key.name(), key.addressHash()) : before.isPresent();
        if (present == (flag == 1)) throw new SecurityException("RVN rule toggle does not change parent status");
        var after = flag == 1 ? Optional.of("1") : Optional.<String>empty();
        // Under the per-block durable/cleared-cache policy, REMOVE erases only
        // the exact assignment. Inherited children remain. An accepted command
        // can therefore have no exact-key mutation and needs no synthetic undo.
        return before.equals(after) ? Optional.empty() : Optional.of(new RvnAssetChanges.RuleUpdate(before, after));
    }

    public static void requireDestination(RvnAssetRulesView parent, String name, String addressHash, Predicate<String> qualifierExists) {
        var expression = parent.findRule(new RvnAssetRuleKey(VERIFIER, name, ""))
                .orElseThrow(() -> new SecurityException("RVN restricted verifier missing"));
        requireVerifier(parent, expression, addressHash, qualifierExists);
        if (parent.findRule(new RvnAssetRuleKey(GLOBAL_FREEZE, name, "")).isPresent())
            throw new SecurityException("RVN restricted asset globally frozen");
    }

    public static void requireSource(RvnAssetRulesView parent, String name, String addressHash) {
        if (parent.findRule(new RvnAssetRuleKey(ADDRESS_FREEZE, name, addressHash)).isPresent())
            throw new SecurityException("RVN restricted source address frozen");
    }

    public static void requireVerifier(RvnAssetRulesView parent, String expression, String addressHash, Predicate<String> qualifierExists) {
        var verifier = RvnRestrictedVerifier.fromOutput(expression);
        for (String qualifier : verifier.qualifiers())
            if (!qualifierExists.test(qualifier)) throw new SecurityException("RVN verifier references missing qualifier");
        if (addressHash != null && !verifier.evaluate(q -> parent.hasQualifier(q, addressHash)))
            throw new SecurityException("RVN restricted destination does not satisfy verifier");
    }
}
