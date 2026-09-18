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
        Objects.requireNonNull(
                candidate,
                "candidate"
        );

        if (candidate.chainWork().compareTo(
                bestHeaderTip.chainWork()
        ) <= 0) {
            return false;
        }

        bestHeaderTip =
                candidate;

        return true;
    }
}