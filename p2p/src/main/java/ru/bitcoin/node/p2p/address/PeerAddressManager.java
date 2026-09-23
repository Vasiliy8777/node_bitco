package ru.bitcoin.node.p2p.address;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

public final class PeerAddressManager {

    public static final int NEW_BUCKET_COUNT =
            1024;

    public static final int TRIED_BUCKET_COUNT =
            256;

    public static final int BUCKET_SIZE =
            64;

    public static final int MAX_NEW_REFERENCES =
            8;

    public static final int MAX_GETADDR =
            1000;

    public static final int GETADDR_PERCENT =
            23;

    private static final Duration HORIZON =
            Duration.ofDays(
                    30
            );

    private static final Duration RETRY_WINDOW =
            Duration.ofMinutes(
                    1
            );

    private static final Duration MIN_FAIL_AGE =
            Duration.ofDays(
                    7
            );

    private static final int MAX_FAILURES =
            10;

    private final Map<
            PeerAddressKey,
            KnownPeerAddress
            > addresses =
            new LinkedHashMap<>();

    private final AddrManBucket[] newBuckets =
            buckets(
                    NEW_BUCKET_COUNT
            );

    private final AddrManBucket[] triedBuckets =
            buckets(
                    TRIED_BUCKET_COUNT
            );

    private final byte[] secretKey;

    private final Random random;

    public PeerAddressManager() {

        this(
                createSecretKey(),
                new SecureRandom()
        );
    }

    PeerAddressManager(
            byte[] secretKey,
            Random random
    ) {

        Objects.requireNonNull(
                secretKey,
                "secretKey"
        );

        if (secretKey.length != 32) {

            throw new IllegalArgumentException(
                    "AddrMan secret key must be 32 bytes"
            );
        }

        this.secretKey =
                secretKey.clone();

        this.random =
                Objects.requireNonNull(
                        random,
                        "random"
                );
    }

    public synchronized KnownPeerAddress add(
            PeerAddress peerAddress,
            Instant seenAt
    ) {

        Objects.requireNonNull(
                peerAddress,
                "peerAddress"
        );

        Objects.requireNonNull(
                seenAt,
                "seenAt"
        );

        PeerAddressKey key =
                PeerAddressKey.from(
                        peerAddress
                );

        KnownPeerAddress existing =
                addresses.get(
                        key
                );

        if (existing != null) {

            existing.seen(
                    peerAddress,
                    seenAt
            );

            if (existing.isNew()
                    && existing.newBucketReferences()
                    < MAX_NEW_REFERENCES) {

                addNewReference(
                        key,
                        existing,
                        existing.newBucketReferences()
                );
            }

            return existing;
        }

        KnownPeerAddress created =
                new KnownPeerAddress(
                        peerAddress,
                        seenAt
                );

        addresses.put(
                key,
                created
        );

        addNewReference(
                key,
                created,
                0
        );

        return created;
    }

    public synchronized void addAll(
            Collection<PeerAddress> peerAddresses,
            Instant seenAt
    ) {

        Objects.requireNonNull(
                peerAddresses,
                "peerAddresses"
        );

        Objects.requireNonNull(
                seenAt,
                "seenAt"
        );

        for (PeerAddress peerAddress :
                peerAddresses) {

            add(
                    Objects.requireNonNull(
                            peerAddress,
                            "peerAddress"
                    ),
                    seenAt
            );
        }
    }

    public synchronized Optional<KnownPeerAddress> find(
            PeerAddress peerAddress
    ) {

        Objects.requireNonNull(
                peerAddress,
                "peerAddress"
        );

        return Optional.ofNullable(
                addresses.get(
                        PeerAddressKey.from(
                                peerAddress
                        )
                )
        );
    }

    public synchronized void markAttempt(
            PeerAddress peerAddress,
            Instant time
    ) {

        known(
                peerAddress
        ).attempted(
                time
        );
    }

    public synchronized void markSuccess(
            PeerAddress peerAddress,
            Instant time
    ) {

        KnownPeerAddress known =
                known(
                        peerAddress
                );

        known.succeeded(
                time
        );

        if (known.isNew()) {

            promoteToTried(
                    PeerAddressKey.from(
                            peerAddress
                    ),
                    known
            );
        }
    }

    public synchronized List<KnownPeerAddress> addresses() {

        return List.copyOf(
                new ArrayList<>(
                        addresses.values()
                )
        );
    }

    /**
     * Returns randomized outbound candidates.
     *
     * The old API returned insertion order, which allowed the
     * first learned network range to dominate outbound dialing.
     */
    public synchronized List<PeerAddress> candidates() {

        return addresses.values()
                .stream()
                .map(
                        KnownPeerAddress::peerAddress
                )
                .toList();
    }

    /**
     * Address sample suitable for GETADDR.
     */
    public synchronized List<KnownPeerAddress> getAddr() {

        if (addresses.isEmpty()) {
            return List.of();
        }

        List<KnownPeerAddress> eligible =
                new ArrayList<>(
                        addresses.values()
                );

        Collections.shuffle(
                eligible,
                random
        );

        int percentageLimit =
                Math.max(
                        1,
                        eligible.size()
                                * GETADDR_PERCENT
                                / 100
                );

        int limit =
                Math.min(
                        MAX_GETADDR,
                        percentageLimit
                );

        return List.copyOf(
                eligible.subList(
                        0,
                        Math.min(
                                limit,
                                eligible.size()
                        )
                )
        );
    }

    public synchronized int size() {
        return addresses.size();
    }

    public synchronized boolean isEmpty() {
        return addresses.isEmpty();
    }

    public synchronized int newSize() {

        return (int) addresses.values()
                .stream()
                .filter(
                        KnownPeerAddress::isNew
                )
                .count();
    }

    public synchronized int triedSize() {

        return (int) addresses.values()
                .stream()
                .filter(
                        KnownPeerAddress::isTried
                )
                .count();
    }

    synchronized byte[] secretKey() {
        return secretKey.clone();
    }

    private void promoteToTried(
            PeerAddressKey key,
            KnownPeerAddress known
    ) {

        removeFromNew(
                key
        );

        PeerAddress address =
                known.peerAddress();

        int bucketIndex =
                triedBucket(
                        address
                );

        int slotIndex =
                triedSlot(
                        address,
                        bucketIndex
                );

        AddrManBucket bucket =
                triedBuckets[
                        bucketIndex
                        ];

        PeerAddressKey collision =
                bucket.get(
                        slotIndex
                );

        if (collision == null
                || collision.equals(
                key
        )) {

            bucket.put(
                    slotIndex,
                    key
            );

            known.promoteToTried();

            return;
        }

        KnownPeerAddress incumbent =
                addresses.get(
                        collision
                );

        /*
         * Do not blindly destroy a working tried address.
         *
         * Replace only an address which has become terrible.
         * Otherwise the successful newcomer remains NEW.
         */
        if (incumbent != null
                && isTerrible(
                incumbent,
                Instant.now()
        )) {

            bucket.put(
                    slotIndex,
                    key
            );

            incumbent.demoteToNew();

            addNewReference(
                    collision,
                    incumbent,
                    0
            );

            known.promoteToTried();

            return;
        }

        /*
         * Promotion collided with a healthy TRIED entry.
         * Keep the successful address in NEW.
         */
        known.demoteToNew();

        addNewReference(
                key,
                known,
                0
        );
    }

    private void addNewReference(
            PeerAddressKey key,
            KnownPeerAddress known,
            int reference
    ) {

        if (!known.isNew()) {
            return;
        }

        if (known.newBucketReferences()
                >= MAX_NEW_REFERENCES) {

            return;
        }

        PeerAddress address =
                known.peerAddress();

        int bucketIndex =
                newBucket(
                        address,
                        reference
                );

        int slotIndex =
                newSlot(
                        address,
                        bucketIndex
                );

        AddrManBucket bucket =
                newBuckets[
                        bucketIndex
                        ];

        if (bucket.contains(
                key
        )) {
            return;
        }

        PeerAddressKey existing =
                bucket.get(
                        slotIndex
                );

        if (existing != null) {

            KnownPeerAddress incumbent =
                    addresses.get(
                            existing
                    );

            if (incumbent != null
                    && !isTerrible(
                    incumbent,
                    Instant.now()
            )) {

                return;
            }

            bucket.remove(
                    slotIndex
            );

            if (incumbent != null) {

                incumbent.decrementNewBucketReferences();

                removeIfUnreferenced(
                        existing,
                        incumbent
                );
            }
        }

        bucket.put(
                slotIndex,
                key
        );

        known.incrementNewBucketReferences();
    }

    private void removeFromNew(
            PeerAddressKey key
    ) {

        KnownPeerAddress known =
                addresses.get(
                        key
                );

        for (AddrManBucket bucket :
                newBuckets) {

            bucket.remove(
                    key
            );
        }

        if (known != null) {
            known.resetNewBucketReferences();
        }
    }

    private void removeIfUnreferenced(
            PeerAddressKey key,
            KnownPeerAddress known
    ) {

        if (known.isNew()
                && known.newBucketReferences() == 0) {

            addresses.remove(
                    key
            );
        }
    }

    private List<KnownPeerAddress> selectableAddresses(
            Instant now
    ) {

        List<KnownPeerAddress> result =
                new ArrayList<>();

        for (KnownPeerAddress known :
                addresses.values()) {

            if (!isTerrible(
                    known,
                    now
            )) {

                result.add(
                        known
                );
            }
        }

        return result;
    }

    private boolean isTerrible(
            KnownPeerAddress known,
            Instant now
    ) {

        Instant lastAttempt =
                known.lastAttempt()
                        .orElse(
                                null
                        );

        if (lastAttempt != null
                && lastAttempt.isAfter(
                now.minus(
                        RETRY_WINDOW
                )
        )) {

            return false;
        }

        if (known.lastSeen()
                .isAfter(
                        now.plus(
                                Duration.ofMinutes(
                                        10
                                )
                        )
                )) {

            return true;
        }

        if (known.lastSeen()
                .isBefore(
                        now.minus(
                                HORIZON
                        )
                )) {

            return true;
        }

        Instant success =
                known.lastSuccess()
                        .orElse(
                                null
                        );

        if (success == null
                && known.attempts()
                >= MAX_FAILURES) {

            return true;
        }

        return success != null
                && success.isBefore(
                now.minus(
                        MIN_FAIL_AGE
                )
        )
                && known.attempts()
                >= MAX_FAILURES;
    }

    private double selectionChance(
            KnownPeerAddress known,
            Instant now
    ) {

        double chance =
                1.0;

        Optional<Instant> lastAttempt =
                known.lastAttempt();

        if (lastAttempt.isPresent()
                && lastAttempt.get()
                .isAfter(
                        now.minus(
                                Duration.ofMinutes(
                                        10
                                )
                        )
                )) {

            chance *=
                    0.01;
        }

        int attempts =
                Math.min(
                        known.attempts(),
                        8
                );

        for (int i = 0;
             i < attempts;
             i++) {

            chance *=
                    0.66;
        }

        return chance;
    }

    private int newBucket(
            PeerAddress address,
            int reference
    ) {

        long hash =
                AddrManHasher.hash64(
                        secretKey,
                        AddrManHasher.groupBytes(
                                address
                        ),
                        AddrManHasher.endpointBytes(
                                address
                        ),
                        AddrManHasher.intBytes(
                                reference
                        )
                );

        return floorMod(
                hash,
                NEW_BUCKET_COUNT
        );
    }

    private int newSlot(
            PeerAddress address,
            int bucket
    ) {

        long hash =
                AddrManHasher.hash64(
                        secretKey,
                        AddrManHasher.endpointBytes(
                                address
                        ),
                        AddrManHasher.intBytes(
                                bucket
                        )
                );

        return floorMod(
                hash,
                BUCKET_SIZE
        );
    }

    private int triedBucket(
            PeerAddress address
    ) {

        long hash =
                AddrManHasher.hash64(
                        secretKey,
                        AddrManHasher.groupBytes(
                                address
                        ),
                        AddrManHasher.endpointBytes(
                                address
                        )
                );

        return floorMod(
                hash,
                TRIED_BUCKET_COUNT
        );
    }

    private int triedSlot(
            PeerAddress address,
            int bucket
    ) {

        long hash =
                AddrManHasher.hash64(
                        secretKey,
                        AddrManHasher.endpointBytes(
                                address
                        ),
                        AddrManHasher.intBytes(
                                bucket
                        )
                );

        return floorMod(
                hash,
                BUCKET_SIZE
        );
    }

    private KnownPeerAddress known(
            PeerAddress peerAddress
    ) {

        Objects.requireNonNull(
                peerAddress,
                "peerAddress"
        );

        KnownPeerAddress known =
                addresses.get(
                        PeerAddressKey.from(
                                peerAddress
                        )
                );

        if (known == null) {

            throw new IllegalArgumentException(
                    "Unknown peer address: "
                            + peerAddress.hostAddress()
                            + ":"
                            + peerAddress.port()
            );
        }

        return known;
    }

    private static AddrManBucket[] buckets(
            int count
    ) {

        AddrManBucket[] result =
                new AddrManBucket[
                        count
                        ];

        for (int i = 0;
             i < count;
             i++) {

            result[i] =
                    new AddrManBucket();
        }

        return result;
    }

    private static byte[] createSecretKey() {

        byte[] result =
                new byte[32];

        new SecureRandom()
                .nextBytes(
                        result
                );

        return result;
    }

    private static int floorMod(
            long value,
            int modulus
    ) {

        return (int) Math.floorMod(
                value,
                (long) modulus
        );
    }
}