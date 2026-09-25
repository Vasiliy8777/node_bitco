package ru.bitcoin.node.p2p.address;

/**
 * Per-connection address-processing token bucket.
 *
 * Bitcoin Core v31.1 starts each peer with one token so a self-announcement
 * can be processed immediately, refills at 0.1 address/second and caps normal
 * refill at MAX_ADDR_TO_SEND (1000).
 */
final class AddressRelayBudget {

    static final int MAX_ADDR_PROCESSING_TOKEN_BUCKET = 1_000;
    static final double MAX_ADDR_RATE_PER_SECOND = 0.1d;
    static final double INITIAL_TOKENS = 1.0d;

    private double addresses = INITIAL_TOKENS;
    private long updated;

    AddressRelayBudget(long now) {
        updated = now;
    }

    synchronized int takeAddresses(
            int requested,
            long now
    ) {

        if (requested < 0) {
            throw new IllegalArgumentException(
                    "requested"
            );
        }

        refill(
                now
        );

        int granted =
                Math.min(
                        requested,
                        (int) addresses
                );

        addresses -= granted;

        return granted;
    }

    synchronized double available(
            long now
    ) {

        refill(
                now
        );

        return addresses;
    }

    private void refill(
            long now
    ) {

        long elapsed =
                now - updated;

        if (elapsed <= 0) {
            return;
        }

        /*
         * Match Core's soft cap semantics:
         *
         * if the bucket is already at/above the normal cap, elapsed time must
         * not grow it further. This also keeps this class compatible with a
         * future GETADDR-response allowance that may temporarily raise the
         * bucket above the normal cap.
         */
        if (addresses < MAX_ADDR_PROCESSING_TOKEN_BUCKET) {

            double seconds =
                    elapsed / 1_000_000_000.0d;

            double increment =
                    seconds
                            * MAX_ADDR_RATE_PER_SECOND;

            addresses =
                    Math.min(
                            MAX_ADDR_PROCESSING_TOKEN_BUCKET,
                            addresses + increment
                    );
        }

        updated = now;
    }
}
