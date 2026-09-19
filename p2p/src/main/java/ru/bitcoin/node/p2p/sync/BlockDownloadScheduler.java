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

        /*
         * A peer is present in this list only while it has
         * no active download.
         */
        List<Peer> availablePeers =
                new ArrayList<>(
                        peers
                );

        ExecutorService executor =
                Executors.newFixedThreadPool(
                        peers.size()
                );

        CompletionService<DownloadResult> completionService =
                new ExecutorCompletionService<>(
                        executor
                );

        int inFlight = 0;
        int completed = 0;

        try {

            while (completed < states.size()) {

                /*
                 * Assign as much work as possible.
                 *
                 * Once a peer receives a block it is removed
                 * from availablePeers until that exact request
                 * completes.
                 */
                boolean assigned;

                do {
                    assigned = false;

                    for (int peerIndex = 0;
                         peerIndex < availablePeers.size();
                         peerIndex++) {

                        Peer peer =
                                availablePeers.get(
                                        peerIndex
                                );

                        DownloadState state =
                                findAssignableState(
                                        peer,
                                        states
                                );

                        if (state == null) {
                            continue;
                        }

                        availablePeers.remove(
                                peerIndex
                        );

                        state.inFlight =
                                true;

                        state.attemptedPeers.add(
                                peer
                        );

                        completionService.submit(
                                () -> download(
                                        peer,
                                        state
                                )
                        );

                        inFlight++;
                        assigned = true;

                        /*
                         * availablePeers changed.
                         */
                        break;
                    }

                } while (assigned);

                if (inFlight == 0) {

                    /*
                     * There is unfinished work, but no request
                     * is active and no available peer can be
                     * assigned to it.
                     *
                     * Therefore every usable peer has already
                     * failed this block.
                     */
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

                /*
                 * Wait for ANY request to finish, not for an
                 * entire batch.
                 *
                 * This is what makes the scheduler
                 * work-conserving.
                 */
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

                /*
                 * NOTFOUND leaves the peer ready.
                 *
                 * Transport/protocol failure causes
                 * BlockDownloadService to close it, so
                 * isReady() becomes false.
                 *
                 * A healthy peer immediately returns to the
                 * available pool and may receive another block
                 * on the next scheduling iteration.
                 */
                if (peer.isReady()) {
                    availablePeers.add(
                            peer
                    );
                }
            }

        } finally {

            /*
             * At normal completion there are no in-flight
             * operations.
             *
             * On exceptional exit interrupt any remaining
             * executor tasks. PeerConnection shutdown remains
             * owned by Peer / PeerManager.
             */
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