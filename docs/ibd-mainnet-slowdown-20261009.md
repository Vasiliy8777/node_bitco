# Mainnet sync slowdown, 2026-10-09

## Observed running process

PID 18856 was syncing mainnet with active height 481354–481355 and header
height 970596. Three thread dumps showed the same dependency:

- `ibd-download-completions` was reading persistent block indexes through
  `CommonAncestorFinder.parentOf` from `CoreBlockDownloadPeerPolicy.refresh`.
- `bitcoin-node-lifecycle` was blocked entering the download session's
  `inFlightPeer` monitor while the refresh held the session monitor.
- The effective JVM flag was `TieredStopAtLevel=1`, despite the explicit
  level 4 VM argument. IntelliJ appended its optimized-launch flag afterwards.
- RocksDB reported zero write stalls and zero pending compaction bytes.
  Pruning was disabled (`getblockchaininfo.pruned=false`).

Artifacts: `target/sync-diagnosis-threads.txt` and
`target/sync-diagnosis-all-threads{,-2,-3}.txt`. The application console was not
available as a saved file; the diagnosis uses thread dumps, RPC and RocksDB LOG.

## Changes

- Accelerated common-ancestor lookup brackets a fork with exponential steps,
  then uses binary search for its exact height. This requires logarithmically
  many ancestor queries instead of reading every parent on both fork branches.
  Each persistent ancestor query uses the existing branch-safe skip index.
  Shallow forks need only a small bracket; lookups without an accelerated
  ancestor interface retain the previous parent traversal.
- Peer policy caches immutable common-ancestor results across peers, bounded
  to 2048 pairs of branch-tip hashes. Availability, failure flags, services and
  download-window eligibility still receive their ordinary checks.
- Refresh computes a copied peer-state snapshot outside the eligibility
  monitor and publishes it atomically. The ancestry cache has its own short
  lock. Refresh is serialized separately from eligibility decisions.
- Download-session entry points refresh peer policy before acquiring the
  session monitor. Assignment no longer performs refresh under that monitor.
  `inFlightPeer` uses the in-flight tracker's existing independent monitor.
- Shared GPU and local default IntelliJ configurations disable
  `ENABLE_LAUNCH_OPTIMIZATION`; VM options retain level 4 JIT and a 4 GiB heap.
  The option name was checked against the installed Spring Boot plugin class.
- Mainnet native RocksDB cache defaults to 512 MiB (previously 128 MiB), with
  `BITCOIN_ROCKSDB_BLOCK_CACHE_MIB` available to override it. The native cache
  is outside the Java heap. The host had about 17 GiB available physical RAM.

## Validation

`target/sync-slowdown-fixes-regression.log`: BUILD SUCCESS; 172 tests in 27
classes, zero failures/errors/skips. Coverage includes common ancestors,
stored ancestry and cancellation, reorganization, block download scheduling,
reconnect, Core peer-policy contracts, node configuration/lifecycle, pruning
and block sync coordination.

The new persistent-fork regression has tips at heights 8192 and 8193 with a
fork at height 17. It verifies the exact ancestor and fewer than 2000 native
gets, instead of reading about 16000 parent indexes. Concurrent tests hold a
refresh behind a latch and verify that session inspection/close and previously
published eligibility remain available. Publication then switches eligibility
to the newly announced branch.

The running node was not restarted. Code, JIT flags and native cache settings
take effect on a fresh launch. No mainnet before/after throughput claim is made;
the cache increase is tuning rather than a demonstrated independent root cause.
