# Ravencoin: parallel lazy DAG, batch reads, diagnostics and self-checks

Source: `node_bitco-main - 2026-10-23T230924.558.zip`.
Only `rvn-pow` changed. No changes to Bitcoin modules.

## Implementation

- Replaces the global synchronized expensive `RvnKawpowLightDag.item()`
  with per-index single-flight computation: concurrent requests for the same
  item share a future; distinct indexes compute concurrently. LRU state is
  protected by a short monitor. Failed calculations release the in-flight slot.
- Preserves bounded LRU, cloning, public API, zero-capacity behavior and
  adds eviction / shared-wait counters.
- `RvnKawpowDagBatch` performs bounded parallel DAG reads, validates indexes,
  preserves input order, propagates exceptions, and cancels outstanding work.
- `RvnKawpowDagPage` provides bounded contiguous snapshots with LE accessors.
- `RvnKawpowDagMetrics` provides diagnostic counters and hit ratio.
- `RvnKawpowDagSelfCheck` compares candidate DAG items against the existing
  light-cache dataset implementation (internal consistency, **not** a
  Ravencoin Core consensus oracle).
- 3 JUnit test classes cover concurrent requests, defensive copies, LRU,
  batch order and errors, pages and corruption detection.

## Build

From repository root, unpack with replacement and run:

    mvn -pl rvn-pow -am test
    mvn -pl rvn-app -am test

## Important limitation

This is **not** a complete KawPoW implementation. ProgPoW's execution loop,
consensus mix/final hash, and real-block reference vectors remain missing.
Likewise the 14 X16R primitives and X16RV2 Tiger192 are incomplete.
