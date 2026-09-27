package ru.bitcoin.node.consensus.deployment;

/** Parameters for one version-bits deployment. */
public record Deployment(
        String name,
        int bit,
        long startTime,
        long timeout,
        int threshold,
        int window,
        long minActivationHeight,
        TransitionPolicy transitionPolicy
) {
    public Deployment {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Deployment name must not be blank");
        if (bit < 0 || bit > 28) throw new IllegalArgumentException("Version-bits bit must be in [0,28]");
        if (threshold <= 0 || window <= 0 || threshold > window)
            throw new IllegalArgumentException("Invalid deployment threshold/window");
        if (minActivationHeight < 0) throw new IllegalArgumentException("minActivationHeight must not be negative");
        if (transitionPolicy == null) throw new IllegalArgumentException("transitionPolicy must not be null");
    }

    /** Standard BIP9 deployment with no additional minimum activation height. */
    public Deployment(String name, int bit, long startTime, long timeout, int threshold, int window) {
        this(name, bit, startTime, timeout, threshold, window, 0, TransitionPolicy.BIP9);
    }

    public enum TransitionPolicy {
        /** BIP9: timeout takes precedence over threshold while STARTED. */
        BIP9,
        /** BIP341 Speedy Trial: threshold takes precedence; DEFINED does not fail on timeout. */
        SPEEDY_TRIAL
    }
}
