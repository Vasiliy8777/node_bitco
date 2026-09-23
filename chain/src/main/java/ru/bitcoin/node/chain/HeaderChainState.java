package ru.bitcoin.node.chain;

import java.util.Objects;
import java.util.function.Supplier;

public final class HeaderChainState {

    private BlockIndex bestHeaderTip;
    private final Supplier<BlockIndex> persistentBestHeaderSupplier;

    public HeaderChainState(BlockIndex bestHeaderTip) {
        this(bestHeaderTip, null);
    }

    HeaderChainState(
            BlockIndex bestHeaderTip,
            Supplier<BlockIndex> persistentBestHeaderSupplier
    ) {
        this.bestHeaderTip = Objects.requireNonNull(bestHeaderTip, "bestHeaderTip");
        this.persistentBestHeaderSupplier = persistentBestHeaderSupplier;
    }

    public synchronized BlockIndex bestHeaderTip() {
        refreshFromPersistence();
        return bestHeaderTip;
    }

    public synchronized boolean consider(BlockIndex candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.chainWork().compareTo(bestHeaderTip.chainWork()) <= 0) {
            return false;
        }
        bestHeaderTip = candidate;
        return true;
    }

    public synchronized boolean isBetterThanBest(BlockIndex candidate) {
        Objects.requireNonNull(candidate, "candidate");
        refreshFromPersistence();
        return candidate.chainWork().compareTo(bestHeaderTip.chainWork()) > 0;
    }

    private void refreshFromPersistence() {
        if (persistentBestHeaderSupplier == null) return;
        BlockIndex persisted = Objects.requireNonNull(
                persistentBestHeaderSupplier.get(),
                "persistent best header tip"
        );
        if (!persisted.hash().equals(bestHeaderTip.hash())) {
            bestHeaderTip = persisted;
        }
    }
}
