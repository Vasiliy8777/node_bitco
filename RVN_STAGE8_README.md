# RVN Stage 8 — candidate DAG, work accounting, and fail-closed PoW integration boundary

Based on the supplied repository snapshot `node_bitco-main - 2026-10-23T201830.773.zip`.
Only NEW RVN files are included; no Bitcoin sources, root pom.xml, existing RVN classes or application config are changed.

## Components
- `RvnHash256`: immutable wire-order 256-bit identifier, with display-order hex conversion. It does not calculate Ravencoin hashes.
- `RvnHeaderCandidate`: metadata record for an externally supplied candidate ID and parent ID.
- `RvnCandidateGraph`: bounded in-memory DAG of UNVERIFIED candidates; parent links, height consistency, forks, duplicate protection, candidate work and accumulated CLAIMED work.
- `RvnHeaderProofGate`: fail-closed interface requiring three independent checks (block ID, algorithm-specific PoW, and contextual difficulty/deployment/time rules). Its default implementation DENIES everything.
- `RvnCandidateGraphTest`: JUnit coverage for branches, duplicates, heights, orphans, hash immutability and fail-closed behavior.

## IMPORTANT SECURITY LIMITATION
The candidate graph DOES NOT verify block hashes, PoW, retargeting, timestamp rules, or chain validity. It must not be treated as the active chain, a trusted block locator source, or a mining template source. The gate is an interface, not a complete verifier. A caller-provided verifier that always returns true would be unsafe. Do not connect it to the active chain before implementing and testing all Ravencoin consensus rules against Core.

This stage is intentionally not enabled automatically. There are no changes to the existing Bitcoin node.

Run (without clean):
  mvn -pl rvn-app -am test
  mvn -pl rvn-app -am package -DskipTests
