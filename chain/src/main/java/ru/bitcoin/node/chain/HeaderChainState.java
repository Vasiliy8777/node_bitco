package ru.bitcoin.node.chain;

import java.util.Objects;

public final class HeaderChainState {

    private BlockIndex bestHeaderTip;

    public HeaderChainState(
            BlockIndex bestHeaderTip
    ) {
        this.bestHeaderTip =
                Objects.requireNonNull(
                        bestHeaderTip,
                        "bestHeaderTip"
                );
    }

    public synchronized BlockIndex bestHeaderTip() {
        return bestHeaderTip;
    }

    public synchronized boolean consider(
            BlockIndex candidate
    ) {
        if (!isBetterThanBest(
                candidate
        )) {
            return false;
        }

        bestHeaderTip =
                candidate;

        return true;
    }

    public synchronized boolean isBetterThanBest(
            BlockIndex candidate
    ) {
        Objects.requireNonNull(
                candidate,
                "candidate"
        );

        return candidate.chainWork().compareTo(
                bestHeaderTip.chainWork()
        ) > 0;
    }
}