package ru.bitcoin.node.app.sync;

import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.p2p.Peer;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class BlockDownloadWindowStallDetector {

    public Optional<Peer> findStallingPeer(
            List<BlockIndex> connectPath,
            int nextToProcess,
            int downloadWindow,
            Function<BlockIndex, Boolean> available,
            Function<BlockIndex, Optional<Peer>> inFlightPeer
    ) {

        Objects.requireNonNull(
                connectPath,
                "connectPath"
        );

        Objects.requireNonNull(
                available,
                "available"
        );

        Objects.requireNonNull(
                inFlightPeer,
                "inFlightPeer"
        );

        if (nextToProcess < 0
                || nextToProcess > connectPath.size()) {

            throw new IllegalArgumentException(
                    "nextToProcess is outside connectPath"
            );
        }

        if (downloadWindow <= 0) {
            throw new IllegalArgumentException(
                    "downloadWindow must be positive"
            );
        }

        if (nextToProcess
                >= connectPath.size()) {
            return Optional.empty();
        }

        int windowEnd =
                Math.min(
                        connectPath.size(),
                        Math.addExact(
                                nextToProcess,
                                downloadWindow
                        )
                );

        /*
         * If the current window already reaches the end of the
         * connect path, there is no work beyond the window whose
         * admission is being prevented.
         */
        if (windowEnd
                >= connectPath.size()) {
            return Optional.empty();
        }

        Peer firstBlockingPeer =
                null;

        for (int i = nextToProcess;
             i < windowEnd;
             i++) {

            BlockIndex index =
                    connectPath.get(i);

            if (Boolean.TRUE.equals(
                    available.apply(index)
            )) {
                continue;
            }

            Optional<Peer> owner =
                    Objects.requireNonNull(
                            inFlightPeer.apply(index),
                            "inFlightPeer returned null"
                    );

            /*
             * There is still an unassigned missing block inside
             * the current window.
             *
             * The window itself is therefore not what prevents
             * more useful work from being scheduled.
             */
            if (owner.isEmpty()) {
                return Optional.empty();
            }

            if (firstBlockingPeer == null) {
                firstBlockingPeer =
                        owner.get();
            }
        }

        return Optional.ofNullable(
                firstBlockingPeer
        );
    }
}