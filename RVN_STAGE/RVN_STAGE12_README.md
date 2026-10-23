# RVN Stage 12 — crash-safe candidate journal with mandatory revalidation

Snapshot: `node_bitco-main - 2026-10-23T210330.117.zip`.

New `RvnVerifiedHeaderJournal` provides:
- Versioned binary file, bounded record count, per-record SHA-256 integrity checks
- Atomic replace using temp file and ATOMIC_MOVE when supported
- Strict EOF/trailing data detection and defensive byte array copies
- Explicit replay into an EMPTY validated index through existing candidate factory,
  format policy and full `RvnHeaderProofGate.Verifier`.
- Tests for round-trip, corruption, truncation, tampering and fail-closed replay.

Security: This is a CANDIDATE journal, not a validated checkpoint. The claimed IDs
and bytes are untrusted. SHA-256 is an accidental-corruption check, NOT authentication.
Never use a permissive TEST verifier in production. No real X16R/X16RV2/KawPoW
engine is provided, so production replay will reject candidates.

This stage does not enable syncing or mining, does not alter BTC, app configs or pom.
Commands:
mvn -pl rvn-app -am test
mvn -pl rvn-app -am package -DskipTests
