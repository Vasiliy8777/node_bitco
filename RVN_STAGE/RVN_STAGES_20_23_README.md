# RVN stages 20–23 — contextual atomic validation fix

Source snapshot: node_bitco-main - 2026-10-23T212339.266.zip

20. Fixed a correctness problem: the Stage 17 context policy held the LIVE index,
    but Stage 13 atomic batch validates against a PRIVATE staged index. As a
    result, a second header in the same batch could not see its verified parent.
21. Added `admitBatchAtomicContextual`, whose verifier factory receives the
    staged index and sees each successfully checked parent during admission.
22. Added `RvnHeaderContextVerifierFactory` to bind MTP, expected difficulty,
    block-ID and PoW callbacks to the staged index.
23. Added `RvnContextualAdmissionService` with existing metrics and atomic rollback.

IMPORTANT: No X16R/X16RV2/KawPoW hash implementations or difficulty retarget
engine are provided here. A permissive checker MUST NEVER be used in production.
Tests use explicit TEST_ONLY stubs. This patch is a correctness fix and integration
layer, not a full Ravencoin consensus engine.

Run: mvn -pl rvn-app -am test
No `clean` needed. Bitcoin modules and root pom unchanged.
