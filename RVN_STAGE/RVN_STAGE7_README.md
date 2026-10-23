# RVN Stage 7 — large, isolated infrastructure patch

Based on the user-provided current archive `node_bitco-main - 2026-10-23T200957.883.zip`.
All files are new; no Bitcoin files, existing RVN sources, root POM or application settings changed.

Added:
- `RvnBlockProof`: mathematically correct per-block work from compact target, additive cumulative work primitive.
- `RvnDifficultyScreen`: numeric nBits/powLimit and supplied-hash comparison; NOT a real RVN PoW verifier.
- `RvnPeerSelector`: round-robin selection using existing bounded pool and exponential backoff.
- `RvnHeadersIngestor`: strict unverified headers batch ingestion into the existing spool, metrics and empty-batch handling.
- `RvnHeadersProbeCoordinator`: one opt-in network getheaders exchange, bounded connection/queue/response timeout, failure backoff, explicit trusted locators.
- JUnit tests for arithmetic, peer scheduling, ingestion, and invalid request rejection.

Important: no chain index, no canonical RVN block ID, no actual KawPoW/X16R/X16RV2 hashing, no expected difficulty algorithm, no validated chainwork or mining.
A numeric work contribution from an unverified nBits field must NEVER be treated as verified chainwork.
The coordinator DOES NOT auto-paginate: it cannot derive a trusted next locator without verified block IDs.
It is not automatically started by the Spring Boot application.

Build from project root (do NOT use clean):
  mvn -pl rvn-app -am test
  mvn -pl rvn-app -am package -DskipTests
