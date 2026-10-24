package ru.bitcoin.node.rvn.chain.assets;

import java.util.*;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetMetadataRules.Metadata;

/** Net metadata/rule changes relative to the parent block, with exact prior values for undo.
 * A storage plan is not evidence of transaction authorization or consensus validity. */
public record RvnAssetChanges(Map<String, Update> entries, Map<RvnAssetRuleKey, RuleUpdate> rules) {
    public static final RvnAssetChanges EMPTY = new RvnAssetChanges(Map.of());
    public RvnAssetChanges(Map<String, Update> entries) { this(entries, Map.of()); }

    public record RuleUpdate(Optional<String> before, Optional<String> after) {
        public RuleUpdate {
            Objects.requireNonNull(before); Objects.requireNonNull(after);
            if (before.equals(after)) throw new IllegalArgumentException("Empty RVN rule mutation");
        }
    }

    public record Update(Optional<Metadata> before, Optional<Metadata> after) {
        public Update {
            Objects.requireNonNull(before); Objects.requireNonNull(after);
            if (before.equals(after)) throw new IllegalArgumentException("Empty RVN asset mutation");
        }
    }

    public RvnAssetChanges {
        entries = Map.copyOf(entries);
        rules = Map.copyOf(rules);
        for (var entry : rules.entrySet()) {
            entry.getValue().before().ifPresent(entry.getKey()::requireValue);
            entry.getValue().after().ifPresent(entry.getKey()::requireValue);
        }
        for (var entry : entries.entrySet()) {
            RvnAssetNames.requireValid(entry.getKey());
            for (var value : List.of(entry.getValue().before(), entry.getValue().after()))
                if (value.isPresent() && !entry.getKey().equals(value.get().name()))
                    throw new IllegalArgumentException("RVN asset change key/name mismatch");
        }
    }
}
