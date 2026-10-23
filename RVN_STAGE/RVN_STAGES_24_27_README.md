# RVN combined stages 24–27: safe contextual restart, checkpoint and PoW digest comparison

Source snapshot: `node_bitco-main - 2026-10-23T213010.388.zip`.

24. `RvnHeaderRecovery.recoverContextual`: restores journal in <=2000 header batches,
    binding the verifier to each staged index. Every header is reverified. Partial
    restored state is never returned if a later batch fails.
25. `RvnHeaderCheckpoint`: explicit immutable trusted bootstrap metadata and
    index factory. Constructor validates shape only, not authenticity.
26. `RvnHeaderRecoveryManager`: recovery plus index audit before publication,
    and pre-save audit.
27. `RvnPowDigest`: unsigned 256-bit comparison with an explicitly specified
    endianness. It accepts only a digest computed by a real external PoW engine.
    Added JUnit tests for recovery, corruption rejection via Stage 12 journal,
    checkpoint mismatch and digest byte order.

**NOT implemented:** X16R, X16RV2, KawPoW, real RVN block ID hashing,
DGW expected nBits, consensus activation rules. A test verifier that
returns true must NEVER be used for production or to populate a trusted index.
Checkpoint must come from an authenticated network-specific source.

No Bitcoin modules, root pom, app startup, P2P or mining modified.

Run:
mvn -pl rvn-app -am test
