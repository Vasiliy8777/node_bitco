package ru.bitcoin.node.chain;

/** Outcome of this submission, not a persistent block-validation status. */
public enum BlockProcessingResult {
    /** The candidate became the active tip after contextual validation and commit. */
    CONNECTED,
    /** Already connected as part of the current active chain. */
    ALREADY_IN_ACTIVE_CHAIN,
    /** Header and structure checked; contextual validation awaits activation. */
    STORED_SIDE_CHAIN_CONTEXT_PENDING,
    /** Parent unavailable. The submitted block was not saved. */
    UNKNOWN_PARENT
}
