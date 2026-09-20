package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.protocol.block.Block;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class BlockDownloadScheduler {

    public static final int MAX_BLOCKS_IN_FLIGHT_PER_PEER = 16;

    private final PeerManager peerManager;
    private final BlockDownloadService blockDownloadService;

    public BlockDownloadScheduler(
            PeerManager peerManager,
            BlockDownloadService blockDownloadService
    ) {
        this.peerManager =
                Objects.requireNonNull(
                        peerManager,
                        "peerManager"
                );

        this.blockDownloadService =
                Objects.requireNonNull(
                        blockDownloadService,
                        "blockDownloadService"
                );
    }

    public List<Block> download(
            List<Hash256> blockHashes
    ) throws IOException {

        Objects.requireNonNull(
                blockHashes,
                "blockHashes"
        );

        if (blockHashes.isEmpty()) {
            return List.of();
        }

        Set<Hash256> uniqueHashes =
                new LinkedHashSet<>();

        for (Hash256 blockHash : blockHashes) {

            Objects.requireNonNull(
                    blockHash,
                    "blockHashes must not contain null"
            );

            if (!uniqueHashes.add(blockHash)) {
                throw new IllegalArgumentException(
                        "Duplicate block hash: "
                                + blockHash.toDisplayHex()
                );
            }
        }

        List<Peer> peers =
                peerManager.readyPeers();

        if (peers.isEmpty()) {
            throw new IOException(
                    "No ready peers available for block download"
            );
        }

        List<DownloadState> states =
                new ArrayList<>(
                        blockHashes.size()
                );

        for (int i = 0;
             i < blockHashes.size();
             i++) {

            states.add(
                    new DownloadState(
                            i,
                            blockHashes.get(i)
                    )
            );
        }

        Block[] results =
                new Block[blockHashes.size()];

        IdentityHashMap<Peer, Integer> activeByPeer =
                new IdentityHashMap<>();

        for (Peer peer : peers) {
            activeByPeer.put(peer, 0);
        }

        int maxParallelDownloads =
                Math.min(
                        states.size(),
                        Math.multiplyExact(
                                peers.size(),
                                MAX_BLOCKS_IN_FLIGHT_PER_PEER
                        )
                );

        ExecutorService executor =
                Executors.newFixedThreadPool(
                        maxParallelDownloads
                );

        CompletionService<DownloadResult> completionService =
                new ExecutorCompletionService<>(
                        executor
                );

        int inFlight = 0;
        int completed = 0;
        int nextPeerIndex = 0;

        try {

            while (completed < states.size()) {

                boolean assigned;

                do {
                    assigned = false;

                    for (int offset = 0;
                         offset < peers.size();
                         offset++) {

                        int peerIndex =
                                (nextPeerIndex + offset)
                                        % peers.size();

                        Peer peer =
                                peers.get(
                                        peerIndex
                                );

                        if (!peer.isReady()) {
                            continue;
                        }

                        int peerInFlight =
                                activeByPeer.getOrDefault(
                                        peer,
                                        0
                                );

                        if (peerInFlight
                                >= MAX_BLOCKS_IN_FLIGHT_PER_PEER) {
                            continue;
                        }

                        DownloadState state =
                                findAssignableState(
                                        peer,
                                        states
                                );

                        if (state == null) {
                            continue;
                        }

                        state.inFlight =
                                true;

                        state.attemptedPeers.add(
                                peer
                        );

                        activeByPeer.put(
                                peer,
                                peerInFlight + 1
                        );

                        completionService.submit(
                                () -> download(
                                        peer,
                                        state
                                )
                        );

                        inFlight++;
                        assigned = true;
                        nextPeerIndex =
                                (peerIndex + 1)
                                        % peers.size();

                        break;
                    }

                } while (assigned);

                if (inFlight == 0) {

                    DownloadState failedState =
                            firstIncomplete(
                                    states
                            );

                    if (failedState == null) {
                        break;
                    }

                    throw buildFailure(
                            failedState
                    );
                }

                DownloadResult downloadResult;

                try {
                    Future<DownloadResult> future =
                            completionService.take();

                    downloadResult =
                            future.get();

                } catch (InterruptedException exception) {

                    Thread.currentThread()
                            .interrupt();

                    throw new IOException(
                            "Block download interrupted",
                            exception
                    );

                } catch (java.util.concurrent.ExecutionException exception) {

                    throw new IOException(
                            "Unexpected block download task failure",
                            exception.getCause()
                    );
                }

                inFlight--;

                DownloadState state =
                        downloadResult.state();

                state.inFlight =
                        false;

                Peer peer =
                        downloadResult.peer();

                int peerInFlight =
                        activeByPeer.getOrDefault(
                                peer,
                                0
                        );

                if (peerInFlight <= 0) {
                    throw new IllegalStateException(
                            "Peer block download count underflow"
                    );
                }

                activeByPeer.put(
                        peer,
                        peerInFlight - 1
                );

                if (downloadResult.block() != null) {

                    Block block =
                            downloadResult.block();

                    if (!state.blockHash.equals(
                            block.hash()
                    )) {

                        IOException exception =
                                new IOException(
                                        "Peer returned unexpected block: expected "
                                                + state.blockHash
                                                .toDisplayHex()
                                                + ", actual "
                                                + block.hash()
                                                .toDisplayHex()
                                );

                        state.failures.add(
                                exception
                        );

                    } else {

                        results[state.index] =
                                block;

                        state.completed =
                                true;

                        completed++;
                    }

                } else {

                    state.failures.add(
                            downloadResult.failure()
                    );
                }
            }

        } finally {
            executor.shutdownNow();
        }

        List<Block> ordered =
                new ArrayList<>(
                        results.length
                );

        for (int i = 0;
             i < results.length;
             i++) {

            Block block =
                    results[i];

            if (block == null) {
                throw buildFailure(
                        states.get(i)
                );
            }

            ordered.add(
                    block
            );
        }

        return List.copyOf(
                ordered
        );
    }

    private DownloadResult download(
            Peer peer,
            DownloadState state
    ) {

        try {

            Block block =
                    blockDownloadService.download(
                            peer,
                            state.blockHash
                    );

            return DownloadResult.success(
                    peer,
                    state,
                    block
            );

        } catch (IOException exception) {

            return DownloadResult.failure(
                    peer,
                    state,
                    exception
            );
        }
    }

    private DownloadState findAssignableState(
            Peer peer,
            List<DownloadState> states
    ) {

        for (DownloadState state : states) {

            if (state.completed
                    || state.inFlight) {
                continue;
            }

            if (state.attemptedPeers.contains(
                    peer
            )) {
                continue;
            }

            return state;
        }

        return null;
    }

    private DownloadState firstIncomplete(
            List<DownloadState> states
    ) {

        for (DownloadState state : states) {

            if (!state.completed) {
                return state;
            }
        }

        return null;
    }

    private IOException buildFailure(
            DownloadState state
    ) {

        IOException failure =
                new IOException(
                        "Unable to download block "
                                + state.blockHash
                                .toDisplayHex()
                );

        for (IOException attemptFailure :
                state.failures) {

            failure.addSuppressed(
                    attemptFailure
            );
        }

        return failure;
    }

    private static final class DownloadState {

        private final int index;
        private final Hash256 blockHash;

        /*
         * Identity semantics are intentional:
         * Peer represents one live peer session.
         */
        private final Set<Peer> attemptedPeers =
                Collections.newSetFromMap(
                        new IdentityHashMap<>()
                );

        private final List<IOException> failures =
                new ArrayList<>();

        private boolean inFlight;
        private boolean completed;

        private DownloadState(
                int index,
                Hash256 blockHash
        ) {
            this.index =
                    index;

            this.blockHash =
                    Objects.requireNonNull(
                            blockHash,
                            "blockHash"
                    );
        }
    }

    private record DownloadResult(
            Peer peer,
            DownloadState state,
            Block block,
            IOException failure
    ) {

        private DownloadResult {
            Objects.requireNonNull(
                    peer,
                    "peer"
            );

            Objects.requireNonNull(
                    state,
                    "state"
            );

            if ((block == null)
                    == (failure == null)) {

                throw new IllegalArgumentException(
                        "Exactly one of block or failure must be present"
                );
            }
        }

        private static DownloadResult success(
                Peer peer,
                DownloadState state,
                Block block
        ) {
            return new DownloadResult(
                    peer,
                    state,
                    Objects.requireNonNull(
                            block,
                            "block"
                    ),
                    null
            );
        }

        private static DownloadResult failure(
                Peer peer,
                DownloadState state,
                IOException failure
        ) {
            return new DownloadResult(
                    peer,
                    state,
                    null,
                    Objects.requireNonNull(
                            failure,
                            "failure"
                    )
            );
        }
    }
}