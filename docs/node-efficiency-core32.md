# Operational review against Bitcoin Core 32.1

Updated 2026-11-02. Block-consensus coverage is recorded separately in
[block-consensus-core32.md](block-consensus-core32.md).

## Corrected in this review

The node rebuilt every cluster's exact linearization whenever relay requested
the mining order or a new mining template was built. Core retains graph ordering
work across unchanged clusters. Repeating graph optimization under the chain
lock adds avoidable CPU work and delays other users of that lock.

`ClusterDiagramCache` now reuses cluster results when membership, modified fees
and sigops-adjusted sizes match. The txid commits to transaction dependencies.
Changed fees, joins, splits, RBF and removals invalidate the affected result.
The cache retains metadata for at most 65,536 transactions, discards obsolete
clusters on the next read and holds no transaction bodies or UTXOs.

`Mempool.miningSnapshot()` publishes immutable entries and their matching ordered
clusters under one monitor. Both ordinary GBT and Stratum template generation
use that snapshot. Selection still applies current weight, sigops and fee
budgets, and full contextual block checks still run before returning a template.
Independent transactions bypass the general maximum-closure solver.

Tests cover 240 reproducible mutation sequences, changing fee deltas and sigops,
cluster joins/splits, parent confirmation/reappearance, replacement, cache limits,
immutable snapshots and cached/uncached selection across resource/fee budgets.
The existing 384 Core C++ cluster vectors and native Core RPC/block comparisons
remain part of the validation.

The final full Bitcoin/RVN package run completed 5009 cases: 4997 executed
successfully, 12 opt-in cases skipped, zero failures/errors. This includes
`PeerWriteDeadlineTest`, the 77-scenario native block matrix, Core estimator/RPC
comparisons, template acceptance and the existing eviction vectors. Raw output:
`target/core-update-audit/core32-efficiency-full-build.log`.

In the recorded 2048-transaction, 128-cluster test, eight repeated reads took
32.5059 ms without reuse and 10.588 ms with reuse. Only the initial 128 cluster
solves ran; repeated reads performed none. This is a local cluster-work comparison,
not a claim that whole-node or network throughput increased by that ratio.

The existing mainnet/GPU configuration was restarted through graceful Spring
Admin shutdown (PID 50484 stopped, PID 28660 started). At 00:06 Asia/Bangkok,
authenticated RPC reported height/headers 974152, IBD false, three connections,
16,521 mempool transactions and an optimal diagram with 7,712 points. CUDA
reported about 2.33 GH/s and 26 accepted shares. The live record is
`target/core-update-audit/core32-efficiency-live-verification.json`.

## Other reviewed operational paths

The project already supplies bounded asynchronous transport writes, write
deadlines, global/per-peer byte reservations, ping/pong liveness, outbound
reconnection/backoff and network-group selection; IBD download windows and stall
recovery; validated block/compact-block relay; bounded orphan handling and relay
rate budgets; disk-backed UTXOs, reorganization undo, restart/reindex recovery,
pruning and optional indexes. Relevant regression suites are included in the
Bitcoin/RVN reactor. `PeerWriteDeadlineTest` specifically verifies active/queued
reservation cleanup, timeout, FIFO and healthy-peer isolation.

This review does not certify complete Core feature or performance parity.
The complete RPC/wallet/Tor/I2P/private-broadcast/ASMap feature set is not present;
the existing snapshot-background RPC report is incomplete. Java/RocksDB memory
accounting and cache scheduling differ from Core. Cluster discovery still walks
the current graph; this cache does not reproduce Core's full incremental graph
engine or its budget-limited intermediate ordering. Historical mainnet replay
and sustained whole-node load testing remain separate validation work.
