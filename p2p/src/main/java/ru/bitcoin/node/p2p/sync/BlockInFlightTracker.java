package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

public final class BlockInFlightTracker {
    private final IdentityHashMap<Peer, Long> downloadingSinceByPeer =
            new IdentityHashMap<>();

    public static final int DEFAULT_MAX_BLOCKS_PER_PEER = 16;

    private final int maxBlocksPerPeer;
    private final LongSupplier nanoTime;

    private final IdentityHashMap<Peer, LinkedHashMap<Hash256, Long>>
            blocksByPeer =
            new IdentityHashMap<>();

    private final HashMap<Hash256, Peer> peerByBlock =
            new HashMap<>();

    public BlockInFlightTracker() {
        this(
                DEFAULT_MAX_BLOCKS_PER_PEER,
                System::nanoTime
        );
    }

    public BlockInFlightTracker(
            int maxBlocksPerPeer
    ) {
        this(
                maxBlocksPerPeer,
                System::nanoTime
        );
    }

    BlockInFlightTracker(
            int maxBlocksPerPeer,
            LongSupplier nanoTime
    ) {

        if (maxBlocksPerPeer <= 0) {
            throw new IllegalArgumentException(
                    "maxBlocksPerPeer must be positive"
            );
        }

        this.maxBlocksPerPeer =
                maxBlocksPerPeer;

        this.nanoTime =
                Objects.requireNonNull(
                        nanoTime,
                        "nanoTime"
                );
    }

    public synchronized int maxBlocksPerPeer() {
        return maxBlocksPerPeer;
    }

    public synchronized boolean canRegister(
            Peer peer
    ) {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        return count(peer)
                < maxBlocksPerPeer;
    }

    public synchronized void register(
            Peer peer,
            Hash256 blockHash
    ) {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        Objects.requireNonNull(
                blockHash,
                "blockHash"
        );

        Peer existingPeer =
                peerByBlock.get(
                        blockHash
                );

        if (existingPeer != null) {
            throw new IllegalStateException(
                    "Block already in flight: "
                            + blockHash.toDisplayHex()
            );
        }

        LinkedHashMap<Hash256, Long> blocks =
                blocksByPeer.computeIfAbsent(
                        peer,
                        ignored ->
                                new LinkedHashMap<>()
                );

        if (blocks.size()
                >= maxBlocksPerPeer) {

            throw new IllegalStateException(
                    "Peer already has maximum number of "
                            + "blocks in flight: "
                            + maxBlocksPerPeer
            );
        }

        long requestedAtNanos =
                nanoTime.getAsLong();

        if (blocks.isEmpty()) {
            downloadingSinceByPeer.put(
                    peer,
                    requestedAtNanos
            );
        }

        Long previous =
                blocks.put(
                        blockHash,
                        requestedAtNanos
                );

        if (previous != null) {
            throw new IllegalStateException(
                    "Block already registered for peer: "
                            + blockHash.toDisplayHex()
            );
        }

        peerByBlock.put(
                blockHash,
                peer
        );
    }

    public synchronized void remove(
            Peer peer,
            Hash256 blockHash
    ) {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        Objects.requireNonNull(
                blockHash,
                "blockHash"
        );

        Peer registeredPeer =
                peerByBlock.get(
                        blockHash
                );

        if (registeredPeer == null) {
            throw new IllegalStateException(
                    "Block is not in flight: "
                            + blockHash.toDisplayHex()
            );
        }

        if (registeredPeer != peer) {
            throw new IllegalStateException(
                    "Block is in flight for a different peer: "
                            + blockHash.toDisplayHex()
            );
        }

        LinkedHashMap<Hash256, Long> blocks =
                blocksByPeer.get(
                        peer
                );

        if (blocks == null
                || blocks.isEmpty()) {

            throw new IllegalStateException(
                    "In-flight tracker indexes are inconsistent for block "
                            + blockHash.toDisplayHex()
            );
        }

        /*
         * IMPORTANT:
         * Determine whether this is the first in-flight block
         * BEFORE removing it from the ordered map.
         */
        Hash256 firstBlockHash =
                blocks.keySet()
                        .iterator()
                        .next();

        boolean removingFirst =
                firstBlockHash.equals(
                        blockHash
                );

        Long removed =
                blocks.remove(
                        blockHash
                );

        if (removed == null) {
            throw new IllegalStateException(
                    "In-flight tracker indexes are inconsistent for block "
                            + blockHash.toDisplayHex()
            );
        }

        peerByBlock.remove(
                blockHash
        );

        if (blocks.isEmpty()) {

            blocksByPeer.remove(
                    peer
            );

            downloadingSinceByPeer.remove(
                    peer
            );

        } else if (removingFirst) {

            /*
             * The first block in this peer's in-flight queue
             * completed. Start the download budget for the new
             * first block from the current monotonic time.
             */
            downloadingSinceByPeer.put(
                    peer,
                    nanoTime.getAsLong()
            );
        }
    }

    public synchronized int downloadingPeerCount() {
        return blocksByPeer.size();
    }

    public synchronized Set<Hash256> removeAll(
            Peer peer
    ) {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        LinkedHashMap<Hash256, Long> blocks =
                blocksByPeer.remove(
                        peer
                );

        downloadingSinceByPeer.remove(
                peer
        );

        if (blocks == null
                || blocks.isEmpty()) {
            return Set.of();
        }

        LinkedHashSet<Hash256> removed =
                new LinkedHashSet<>(
                        blocks.keySet()
                );

        for (Hash256 blockHash : removed) {

            Peer registeredPeer =
                    peerByBlock.get(
                            blockHash
                    );

            if (registeredPeer != peer) {
                throw new IllegalStateException(
                        "In-flight tracker indexes are inconsistent for block "
                                + blockHash.toDisplayHex()
                );
            }

            peerByBlock.remove(
                    blockHash
            );
        }

        return Collections.unmodifiableSet(
                removed
        );
    }

    public synchronized int otherDownloadingPeerCount(
            Peer peer
    ) {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        int count =
                blocksByPeer.size();

        if (blocksByPeer.containsKey(peer)) {
            count--;
        }

        return count;
    }

    public synchronized long downloadingSinceNanos(
            Peer peer
    ) {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        Long downloadingSince =
                downloadingSinceByPeer.get(
                        peer
                );

        if (downloadingSince == null) {
            throw new IllegalStateException(
                    "Peer has no blocks in flight"
            );
        }

        return downloadingSince;
    }

    public synchronized long downloadingAgeNanos(
            Peer peer
    ) {

        long downloadingSince =
                downloadingSinceNanos(
                        peer
                );

        return Math.max(
                0L,
                nanoTime.getAsLong()
                        - downloadingSince
        );
    }

    public synchronized int count(
            Peer peer
    ) {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        Map<Hash256, Long> blocks =
                blocksByPeer.get(
                        peer
                );

        if (blocks == null) {
            return 0;
        }

        return blocks.size();
    }

    public synchronized Peer peerForBlock(
            Hash256 blockHash
    ) {

        Objects.requireNonNull(
                blockHash,
                "blockHash"
        );

        return peerByBlock.get(
                blockHash
        );
    }

    public synchronized boolean contains(
            Hash256 blockHash
    ) {

        Objects.requireNonNull(
                blockHash,
                "blockHash"
        );

        return peerByBlock.containsKey(
                blockHash
        );
    }

    public synchronized boolean contains(
            Peer peer,
            Hash256 blockHash
    ) {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        Objects.requireNonNull(
                blockHash,
                "blockHash"
        );

        return peerByBlock.get(
                blockHash
        ) == peer;
    }

    public synchronized Set<Hash256> blocks(
            Peer peer
    ) {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        Map<Hash256, Long> blocks =
                blocksByPeer.get(
                        peer
                );

        if (blocks == null) {
            return Set.of();
        }

        return Collections.unmodifiableSet(
                new LinkedHashSet<>(
                        blocks.keySet()
                )
        );
    }

    public synchronized long requestedAtNanos(
            Peer peer,
            Hash256 blockHash
    ) {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        Objects.requireNonNull(
                blockHash,
                "blockHash"
        );

        if (peerByBlock.get(
                blockHash
        ) != peer) {

            throw new IllegalStateException(
                    "Block is not in flight for peer: "
                            + blockHash.toDisplayHex()
            );
        }

        Map<Hash256, Long> blocks =
                blocksByPeer.get(
                        peer
                );

        if (blocks == null) {
            throw new IllegalStateException(
                    "In-flight tracker indexes are inconsistent for block "
                            + blockHash.toDisplayHex()
            );
        }

        Long requestedAt =
                blocks.get(
                        blockHash
                );

        if (requestedAt == null) {
            throw new IllegalStateException(
                    "In-flight tracker indexes are inconsistent for block "
                            + blockHash.toDisplayHex()
            );
        }

        return requestedAt;
    }

    public synchronized long ageNanos(
            Peer peer,
            Hash256 blockHash
    ) {

        long requestedAt =
                requestedAtNanos(
                        peer,
                        blockHash
                );

        long now =
                nanoTime.getAsLong();

        return Math.max(
                0L,
                now - requestedAt
        );
    }

    public synchronized OldestRequest oldestRequest(
            Peer peer
    ) {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        LinkedHashMap<Hash256, Long> blocks =
                blocksByPeer.get(
                        peer
                );

        if (blocks == null
                || blocks.isEmpty()) {
            return null;
        }

        Map.Entry<Hash256, Long> oldest =
                blocks.entrySet()
                        .iterator()
                        .next();

        long now =
                nanoTime.getAsLong();

        long age =
                Math.max(
                        0L,
                        now - oldest.getValue()
                );

        return new OldestRequest(
                oldest.getKey(),
                oldest.getValue(),
                age
        );
    }

    public record OldestRequest(
            Hash256 blockHash,
            long requestedAtNanos,
            long ageNanos
    ) {

        public OldestRequest {
            Objects.requireNonNull(
                    blockHash,
                    "blockHash"
            );

            if (ageNanos < 0) {
                throw new IllegalArgumentException(
                        "ageNanos must not be negative"
                );
            }
        }
    }
}