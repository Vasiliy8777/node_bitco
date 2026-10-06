# IBD index persistence and peer ancestry, 2026-10-06

Reference: Bitcoin Core v31.1
([validation.cpp](https://github.com/bitcoin/bitcoin/blob/v31.1/src/validation.cpp),
[net_processing.cpp](https://github.com/bitcoin/bitcoin/blob/v31.1/src/net_processing.cpp)).
Core shares immutable block-index ancestry in memory and records body availability
separately from chainstate connection. The Java implementation still uses bounded
caches and a connection-driven download window; these changes do not establish
complete architectural or consensus parity.

## Committed header index

BlockProcessor now supplies its previously resolved committed index to the atomic
body/chainstate commit. KnownBlockStorage omits index writes only when every stored
immutable field matches. The processor enables this optimization only for a
StoredBlockIndexLookup backed by the exact same index store as KnownBlockStorage.
Generic/overlay lookups, unknown headers and changed records use regular persistence,
including removal of obsolete work/height secondary keys. Body, availability,
undo and UTXO commits retain their existing durability order and validation paths.

Regression checks prove zero BLOCK_INDEX reads and zero namespace revision changes
for the unchanged-index body commit, verify body/index/availability after reopening,
and verify replacement leaves one work and one height entry. An index-presence
hint never skips structure, witness, signet, contextual input or script checks.

`target/ibd-core-policy/speed-known-index-tests.log`: BUILD SUCCESS, 79 tests,
zero failures/errors, one opt-in benchmark skipped. Includes Core roundtrip/reorg,
block rejection/retry, and 15 process-crash recovery tests.

First public run: `testnet-known-index-console.log`, existing K:\BitcoinJavaNode\testnet3.
85 one-second observations after first positive rate, 759276 to 768290, zero
zero-rate samples before shutdown. This is an observational run on a different
historical range, not a matched performance comparison. Native telemetry still
showed substantial peer-policy ancestry I/O: one interval processed 544 blocks
with 8300 lookup misses, most samples attributed to CoreBlockDownloadPeerPolicy.refresh.
Process PID 23084 exited normally before the next build.

## Shared peer ancestry proofs

CoreBlockDownloadPeerPolicy now retains at most 2048 ancestor hashes in an LRU,
keyed by both best-known tip hash and target height. Peers announcing the same tip
reuse the proof; a different tip/branch has a different key. For a requested block,
the cached ancestor hash is compared to that block's hash, so a different block
at the same height cannot reuse a positive answer. Refresh reuses this proof when
the connected active tip belongs to the peer's branch, otherwise it uses the
existing common-ancestor finder.

Only immutable ancestry results are cached. Failure predicates, chainwork,
witness/network/limited services, snapshot branch, IBD state and the 1024/1025
window checks continue to run for each decision. This reduces database traversal
without changing which peer is eligible or advancing the window on unvalidated data.
A regression checks that 80 decisions across eight peers need one ancestry traversal,
that active-frontier advancement reuses it, and that a competing same-height block,
new best-known branch and newly failed block are handled correctly.

`speed-peer-ancestry-tests.log`: BUILD SUCCESS, 71 tests, zero failures/errors,
one opt-in benchmark skipped. Includes the five Core IBD policy contract tests,
six peer-policy tests, block processor/storage, moving-window coordinator,
validation and real Core roundtrip/reorg tests.

Final public run: `target/ibd-core-policy/testnet-peer-ancestry-console.log`.
128 one-second observations after first positive rate, 769009 to 784878;
two zero-rate samples (1.6%), consecutive. Typical telemetry on this mostly
coinbase range was 120–140 blocks/s. Shutdown completed in 42 ms, and PID 45004
was verified absent. A representative interval processed 659 blocks with 664
lookup misses, sampled from download-path materialization. However a later
interval still had 10383 misses attributed mainly to peer-policy refresh;
new peer/locator and common-ancestor paths can still produce cold ancestry bursts.
The bounded proof cache does not eliminate all index I/O. These different-range
public runs are not an A/B comparison and cannot establish a speedup percentage.

## Availability locator reuse

Peer-policy refresh formerly rebuilt the identical best-header-parent locator for
each peer requesting headers. It now retains one immutable list keyed by the
best-header hash. New peers and timed retries reuse it; a different header tip,
including a same-height replacement, rebuilds it. The parent anchor, request
contents, per-peer retry interval and eligibility rules are unchanged. This is
a Java storage optimization, not a claim that Core has this exact locator cache.
Core's parent anchor for initial headers is documented in
[SendMessages](https://github.com/bitcoin/bitcoin/blob/v31.1/src/net_processing.cpp#L5350).
The regression checks one locator traversal for eight peers, reuse by a newly
connected peer, and rebuilding after header-tip advancement.

Verification: `speed-locator-tests.log`, BUILD SUCCESS, 41 tests, zero failures/errors,
one opt-in benchmark skipped (Core contracts, peer policy, coordinator and actual
Core roundtrip/reorganization). `testnet-locator-console.log` captured the public
run using the existing K database: 102 one-second observations after first positive
rate, 784981 to 798300, no zero-rate samples before shutdown. Mostly simple blocks
showed approximately 130–140 blocks/s. At height 790460, 704 blocks produced 697
lookup misses sampled from connectWindow, rather than peer-policy refresh. This
short different-range run does not prove that all cold-ancestry bursts are gone
or that the speed equals Core. PID 203232 exited normally; STOPPING to STOPPED was
72 ms. No consensus checks or timeout/window constants changed in this step.

## Remaining differences after locator reuse

Download-window advancement still follows connected blocks. Separating verified
body reception/persistence from ActivateBestChain-style connection requires explicit
linked-body bookkeeping, bounded buffering/backpressure, mutated-body error handling,
and crash/restart tests. Increasing the window or merely trusting received bytes
would not provide Core-equivalent behavior. Public-network speed also depends on
block/input workload, peers and storage. Neither short public runs nor small regtest
fixtures prove equal speed or full consensus equivalence.
