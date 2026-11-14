package ru.bitcoin.node.script;

/** ESTIMATE is Core's dummy checker, exclusively for offline size estimation. */
public enum SignatureCheckMode {
    VERIFY,
    VERIFY_WITHOUT_PRECOMPUTED,
    ESTIMATE
}
