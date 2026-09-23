package ru.bitcoin.node.p2p.address;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

public final class PeerAddressManager {

    public static final int NEW_BUCKET_COUNT =
            1024;

    public static final int TRIED_BUCKET_COUNT =
            256;

    public static final int BUCKET_SIZE =
            64;

    public static final int MAX_NEW_REFERENCES =
            8;

    public static final int NEW_BUCKETS_PER_SOURCE_GROUP =
            64;

    public static final int TRIED_BUCKETS_PER_GROUP =
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

    /*
     * Bitcoin Core:
     * new nodes become terrible after 3 failed attempts.
     */
    private static final int RETRIES =
            3;

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

    /*
     * Backward-compatible API.
     *
     * Locally configured/DNS-discovered addresses do not have
     * a remote gossip source in the current architecture, so
     * use the address itself as its source group.
     */
    public synchronized KnownPeerAddress add(
            PeerAddress peerAddress,
            Instant seenAt
    ) {

        return add(
                peerAddress,
                PeerAddressSource.self(
                        peerAddress
                ),
                seenAt
        );
    }

    public synchronized KnownPeerAddress add(
            PeerAddress peerAddress,
            PeerAddressSource source,
            Instant seenAt
    ) {

        Objects.requireNonNull(
                peerAddress,
                "peerAddress"
        );

        Objects.requireNonNull(
                source,
                "source"
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

            maybeAddNewReference(
                    key,
                    existing
            );

            return existing;
        }

        KnownPeerAddress created =
                new KnownPeerAddress(
                        peerAddress,
                        source,
                        seenAt
                );

        addresses.put(
                key,
                created
        );

        /*
         * Try to place the address into its deterministic NEW bucket.
         *
         * Failure to obtain a NEW bucket slot must NOT remove the address
         * from the known-address index.
         *
         * A bucket collision means only that this address currently has no
         * NEW-table reference. It does not mean that the endpoint itself is
         * unknown or invalid.
         *
         * This distinction is important for:
         *  - explicitly configured peers;
         *  - DNS-discovered peers;
         *  - multiple endpoints belonging to the same network group;
         *  - outbound slot filling when another address is already connected.
         */
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
                    known,
                    time
            );
        }
    }

    public synchronized List<KnownPeerAddress> addresses() {

        return List.copyOf(
                addresses.values()
        );
    }

    /*
     * Compatibility/debug API.
     *
     * This intentionally preserves insertion order.
     * Actual outbound selection is performed by select().
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
     * Select one outbound address from AddrMan.
     *
     * Tried and new tables are sampled separately. When both
     * contain entries, choose between them randomly, then apply
     * the address selection chance to candidates from that table.
     */
    public synchronized Optional<PeerAddress> select(
            Set<PeerAddress> excludedAddresses
    ) {

        Objects.requireNonNull(
                excludedAddresses,
                "excludedAddresses"
        );

        if (addresses.isEmpty()) {
            return Optional.empty();
        }

        List<KnownPeerAddress> newEntries =
                eligible(
                        AddrManState.NEW,
                        excludedAddresses
                );

        List<KnownPeerAddress> triedEntries =
                eligible(
                        AddrManState.TRIED,
                        excludedAddresses
                );

        if (newEntries.isEmpty()
                && triedEntries.isEmpty()) {

            return Optional.empty();
        }

        Instant now =
                Instant.now();

        List<KnownPeerAddress> selectedTable;

        if (newEntries.isEmpty()) {

            selectedTable =
                    triedEntries;

        } else if (triedEntries.isEmpty()) {

            selectedTable =
                    newEntries;

        } else {

            selectedTable =
                    random.nextBoolean()
                            ? triedEntries
                            : newEntries;
        }

        KnownPeerAddress selected =
                selectByChance(
                        selectedTable,
                        now
                );

        return Optional.of(
                selected.peerAddress()
        );
    }

    public synchronized Optional<PeerAddress> select() {

        return select(
                Set.of()
        );
    }

    /**
     * Random sample for a GETADDR response.
     */
    public synchronized List<KnownPeerAddress> getAddr() {

        if (addresses.isEmpty()) {
            return List.of();
        }

        Instant now =
                Instant.now();

        List<KnownPeerAddress> eligible =
                new ArrayList<>();

        for (KnownPeerAddress known :
                addresses.values()) {

            if (!isTerrible(
                    known,
                    now
            )) {

                eligible.add(
                        known
                );
            }
        }

        if (eligible.isEmpty()) {
            return List.of();
        }

        Collections.shuffle(
                eligible,
                random
        );

        int percentageLimit =
                eligible.size()
                        * GETADDR_PERCENT
                        / 100;

        /*
         * For a non-empty small AddrMan we still want GETADDR
         * to be capable of returning an address.
         */
        if (percentageLimit == 0) {
            percentageLimit = 1;
        }

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

    private List<KnownPeerAddress> eligible(
            AddrManState state,
            Set<PeerAddress> excludedAddresses
    ) {

        List<KnownPeerAddress> result =
                new ArrayList<>();

        for (KnownPeerAddress known :
                addresses.values()) {

            if (known.state() != state) {
                continue;
            }

            if (excludedAddresses.contains(
                    known.peerAddress()
            )) {
                continue;
            }

            result.add(
                    known
            );
        }

        return result;
    }

    private KnownPeerAddress selectByChance(
            List<KnownPeerAddress> entries,
            Instant now
    ) {

        if (entries.isEmpty()) {
            throw new IllegalArgumentException(
                    "entries must not be empty"
            );
        }

        /*
         * Rejection sampling.
         *
         * Failed/recently-attempted addresses remain selectable,
         * but with lower probability. This is materially
         * different from filtering them out completely.
         */
        double chanceFactor =
                1.0;

        for (int rounds = 0;
             rounds < 1_000;
             rounds++) {

            KnownPeerAddress candidate =
                    entries.get(
                            random.nextInt(
                                    entries.size()
                            )
                    );

            double chance =
                    selectionChance(
                            candidate,
                            now
                    );

            if (random.nextDouble()
                    < Math.min(
                    1.0,
                    chanceFactor * chance
            )) {

                return candidate;
            }

            chanceFactor *=
                    1.2;
        }

        /*
         * Defensive fallback. AddrMan selection must always make
         * progress if an eligible entry exists.
         */
        return entries.get(
                random.nextInt(
                        entries.size()
                )
        );
    }

    private void maybeAddNewReference(
            PeerAddressKey key,
            KnownPeerAddress known
    ) {

        if (!known.isNew()) {
            return;
        }

        int references =
                known.newBucketReferences();

        if (references >= MAX_NEW_REFERENCES) {
            return;
        }

        /*
         * Multiplicity should become progressively harder to
         * increase. 1 / 2^references.
         */
        int denominator =
                1 << Math.min(
                        references,
                        30
                );

        if (random.nextInt(
                denominator
        ) != 0) {
            return;
        }

        addNewReference(
                key,
                known,
                references
        );
    }

    private void promoteToTried(
            PeerAddressKey key,
            KnownPeerAddress known,
            Instant now
    ) {

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

            removeFromNew(
                    key
            );

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
         * Full Core uses tried-collision tracking plus feeler
         * connections before eviction. That subsystem is the
         * next networking-security step.
         *
         * For now only an objectively terrible incumbent may
         * be displaced automatically.
         */
        if (incumbent != null
                && isTerrible(
                incumbent,
                now
        )) {

            removeFromNew(
                    key
            );

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
        }
    }

    private boolean addNewReference(
            PeerAddressKey key,
            KnownPeerAddress known,
            int reference
    ) {

        if (!known.isNew()) {
            return false;
        }

        if (known.newBucketReferences()
                >= MAX_NEW_REFERENCES) {

            return false;
        }

        PeerAddress address =
                known.peerAddress();

        int bucketIndex =
                newBucket(
                        address,
                        known.source(),
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
            return false;
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

            boolean replace =
                    incumbent == null
                            || incumbent.newBucketReferences() > 1
                            || isTerrible(
                            incumbent,
                            Instant.now()
                    );

            if (!replace) {
                return false;
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

        return true;
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

    private boolean isTerrible(
            KnownPeerAddress known,
            Instant now
    ) {

        Instant lastAttempt =
                known.lastAttempt()
                        .orElse(
                                null
                        );

        /*
         * Never declare an address terrible immediately after
         * attempting it.
         */
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

        /*
         * Never-successful addresses are abandoned much sooner.
         */
        if (success == null
                && known.attempts()
                >= RETRIES) {

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
            PeerAddressSource source,
            int reference
    ) {

        /*
         * Stage 1:
         * source group chooses one of 64 candidate NEW buckets.
         */
        long sourceHash =
                AddrManHasher.hash64(
                        secretKey,
                        AddrManHasher.groupBytes(
                                source
                        ),
                        AddrManHasher.intBytes(
                                reference
                        )
                );

        int sourceBucket =
                floorMod(
                        sourceHash,
                        NEW_BUCKETS_PER_SOURCE_GROUP
                );

        /*
         * Stage 2:
         * address group chooses the final bucket from the
         * source group's candidate space.
         */
        long bucketHash =
                AddrManHasher.hash64(
                        secretKey,
                        AddrManHasher.groupBytes(
                                address
                        ),
                        AddrManHasher.groupBytes(
                                source
                        ),
                        AddrManHasher.intBytes(
                                sourceBucket
                        )
                );

        return floorMod(
                bucketHash,
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

        long first =
                AddrManHasher.hash64(
                        secretKey,
                        AddrManHasher.endpointBytes(
                                address
                        )
                );

        int groupBucket =
                floorMod(
                        first,
                        TRIED_BUCKETS_PER_GROUP
                );

        long second =
                AddrManHasher.hash64(
                        secretKey,
                        AddrManHasher.groupBytes(
                                address
                        ),
                        AddrManHasher.intBytes(
                                groupBucket
                        )
                );

        return floorMod(
                second,
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