package ru.bitcoin.node.chain;

import ru.bitcoin.node.chain.utxo.BlockReorganizationChanges;
import java.util.Objects;

/** A fully loaded and validated reorganization that has not yet been committed. */
public record PreparedChainReorganization(ChainUpdate update, BlockReorganizationChanges changes) {
    public PreparedChainReorganization {
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(changes, "changes");
    }
}
