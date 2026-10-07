# Bounded block-index prefetch for connection windows

Bitcoin Core keeps CBlockIndex parent/skip links in memory:
https://github.com/bitcoin/bitcoin/blob/v31.1/src/chain.h . Our persisted Java
index needs cold database reads when materializing a connection window. The
preceding live run showed about one primary index read per connected block,
with cold primary/skip reads dominating several measured intervals.

## Implementation

Before following the selected best-header branch, BlockSyncCoordinator asks the
existing shared StoredBlockIndexLookup to warm records in the connection window's
height range. RocksDbBlockIndexStore seeks directly into the height namespace,
visits at most 2048 entries and reads uncached primary records with native MultiGet
in chunks of at most 256. The existing bounded LRU retains immutable records.
No new service or NodeConfiguration cache is required.

Height entries are hints only. Connection still starts from the selected tip's
ancestor and follows exact parent hashes, checking heights. Forks may warm records
from multiple branches; a crowded range simply exhausts the budget and remaining
records fall back to ordinary lookup. Missing primary records and stale height
entries are ignored; mismatched primary hashes raise corruption errors, as in
ordinary lookup. No negative cache, chain selection, failure-state cache, body
availability or consensus rule is introduced.

The path does not migrate an unversioned height index. It uses an existing version
1 index or falls back to ordinary reads. Cancellation is checked during scanning,
between native chunks and during cache publication. There are no database writes.

## Verification

`target/ibd-core-policy/height-hint-prefetch-tests.log`: BUILD SUCCESS. New real
RocksDB tests cover two branches at every height, zero primary reads after warming,
no repeated primary reads for a warm range, a bounded scan, cancellation and later
arrival of an unknown header without triggering migration. Existing coordinator,
peer eligibility, Core IBD contracts and NodeConfiguration tests also pass.

Full direct-goal reactor verification:
`target/ibd-core-policy/height-hint-full-tests.log`, BUILD SUCCESS in 2m27s,
3937 tests in 360 classes, zero failures/errors, six skipped. This includes actual
Bitcoin Core roundtrip/reorg and process-crash tests. Direct compilation/Surefire
goals use existing unchanged resources; see the previous reception report for the
Windows resource-lock reason for using this invocation.

## Public testnet observation

`target/ibd-core-policy/testnet-height-hint-console.log` records the run on the
existing K:/BitcoinJavaNode/testnet3 database. After the first positive speed and
before shutdown: 156 one-second observations, heights 831922 to 846049, one
zero-rate observation, mean displayed speed 90.74 blocks/s and median 91 blocks/s.
The preceding shared-tip run had 139 observations and ten zeros at different
heights. This is an uncontrolled observation, not proof of a causal improvement
or equivalent Bitcoin Core performance. Peer disconnect/reconnect events remain.

A representative five-second interval at height 838453 processed 472 blocks at
93.5 blocks/s. Primary reads: 465 keys/3146.7 ms, skip reads: 147/1224.4 ms.
Ordinary lookup misses fell to 132; bulk reads are still counted as keys by database
telemetry. Cold I/O remains significant. The node stopped cleanly at 13:32:04
local time and its process (PID 91912) was verified absent. The database was not reset.

This change batches cold reads; it does not eliminate their disk cost or establish
Core speed parity. A public-network comparison across different blocks cannot
isolate its throughput effect. The received-body frontier remains separate work.
