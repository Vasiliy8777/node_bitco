# RVN combined stages 13–15: atomic admission, journal recovery, admission service

Grounded in `node_bitco-main - 2026-10-23T210905.166.zip`.

## Stage 13 — Atomic batches
- `RvnValidatedHeaderIndex.admitBatchAtomic`: validates the entire batch on a
  private copy and commits index changes only if all candidates pass.
- Preserves existing `admit` behavior; `RvnHeaderBatchAdmission.admitAtomic`
  is a convenience wrapper.
- Rejects structural errors, capacity overflow, or failed PoW gate without
  partially changing the live index.
- Retains insertion order for export.

## Stage 14 — Recovery and persistence
- `RvnValidatedHeaderIndex.journalRecords`: export candidates in parent-first order.
- `RvnHeaderRecovery.save`: atomic journal write via existing Stage 12 journal.
- `RvnHeaderRecovery.recover`: reconstruct a NEW index, reverify every entry,
  publish only after full success; never trusts saved PoW status.

## Stage 15 — Admission service and metrics
- `RvnHeaderAdmissionService`: one atomic entry point with success/rejection counters.
- `RvnHeaderAdmissionMetrics`: thread-safe aggregate counters, snapshot API.
- Tests cover atomic proof rejection, capacity rollback, preserved earlier state,
  journal round trip, fail-closed recovery, metrics.

## Critical consensus limitations
This is NOT a functioning RVN PoW engine. X16R, X16RV2, KawPoW, network
difficulty retarget, and complete header contextual verification are NOT implemented.
`TEST_ONLY` verifiers exist solely in test sources. Never wire them into production.
A genuine trusted anchor and full verifier are mandatory for meaningful chainwork.
No automatic sync or mining is activated. No BTC module, root POM, or config changed.

Run without `clean`:
mvn -pl rvn-app -am test
mvn -pl rvn-app -am package -DskipTests
