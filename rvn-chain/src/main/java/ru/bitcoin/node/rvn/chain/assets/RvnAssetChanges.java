package ru.bitcoin.node.rvn.chain.assets;

import java.util.*;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetMetadataRules.Metadata;

/** Net metadata changes relative to the parent block, with exact prior values for undo.
 * A storage plan is not evidence of transaction authorization or consensus validity. */
public record RvnAssetChanges(Map<String, Update> entries) {
    public static final RvnAssetChanges EMPTY = new RvnAssetChanges(Map.of());

    public record Update(Optional<Metadata> before, Optional<Metadata> after) {
        public Update {
            Objects.requireNonNull(before); Objects.requireNonNull(after);
            if (before.equals(after)) throw new IllegalArgumentException("Empty RVN asset mutation");
        }
    }

    public RvnAssetChanges {
        entries = Map.copyOf(entries);
        for (var entry : entries.entrySet()) {
            RvnAssetNames.requireValid(entry.getKey());
            for (var value : List.of(entry.getValue().before(), entry.getValue().after()))
                if (value.isPresent() && !entry.getKey().equals(value.get().name()))
                    throw new IllegalArgumentException("RVN asset change key/name mismatch");
        }
    }
}
