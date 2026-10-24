package ru.bitcoin.node.rvn.chain.assets;

import java.util.Optional;

/** Stable parent-block rule snapshot. Block-local mutations must not be exposed
 * to restricted transfer checks (Core's fSkipTempCache semantics). */
public interface RvnAssetRulesView {
    Optional<String> findRule(RvnAssetRuleKey key);
    /** Includes any assigned subqualifier when querying its root qualifier. */
    boolean hasQualifier(String name, String addressHash);
}
