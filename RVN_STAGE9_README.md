# RVN Stage 9 — fail-closed validated index infrastructure

Based on user snapshot `node_bitco-main - 2026-10-23T202339.456.zip`.
Only NEW files, all in `rvn-consensus`, plus this README. No Bitcoin files changed.

## Included
1. RvnHeaderWireFields: independent 80/120-byte header metadata decoding and exact comparison with candidate fields.
2. RvnValidatedHeaderIndex: bounded, in-memory parent-linked index with per-header work, cumulative work, fork ranking, path and common ancestor.
3. RvnHeaderOrphanBuffer: bounded staging of unverified headers awaiting a parent.
4. RvnValidatedHeaderIndexTest: fail-closed admission, metadata mismatch, fork and capacity tests.
5. RvnHeaderOrphanBufferTest: deduplication, eviction, defensive-copy tests.

## Security/consensus boundary
The index does not calculate X16R, X16RV2 or KawPoW block hashes, does not validate
network difficulty adjustments, and does not contain a real verifier.
`RvnHeaderProofGate.unavailable()` rejects all new admissions. The permissive
verifier in tests is TEST-ONLY and MUST NEVER be used in the running node.

Do not instantiate this index with an unverified anchor, or connect a fake verifier.
`bestId` is only meaningful when the anchor and ALL admitted headers were independently
validated by a complete, consensus-correct verifier.

This stage does not alter the app startup or activate automatic sync.

## Run (without clean)
mvn -pl rvn-app -am test
mvn -pl rvn-app -am package -DskipTests

Main Java sources compiled with javac 21 locally. Maven/JUnit must run in your environment.
