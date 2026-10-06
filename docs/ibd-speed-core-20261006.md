# IBD speed and stalls: Bitcoin Core comparison, 2026-10-06

Reference executable: Bitcoin Core v31.1.0. Source comparison uses the pinned
[net_processing.cpp](https://github.com/bitcoin/bitcoin/blob/v31.1/src/net_processing.cpp),
[validation.cpp](https://github.com/bitcoin/bitcoin/blob/v31.1/src/validation.cpp),
[blockstorage.cpp](https://github.com/bitcoin/bitcoin/blob/v31.1/src/node/blockstorage.cpp),
and [dbwrapper.cpp](https://github.com/bitcoin/bitcoin/blob/v31.1/src/dbwrapper.cpp).

| Core mechanism | Java finding and change |
| --- | --- |
| 16 in-flight blocks per peer, 1024-block window, release/refill on receipt | Existing limits retained; completion worker keeps downloads moving during validation. Activation slices reduced from 512 to 32 so the window tail is exposed sooner. Core constructs paths in chunks of at most 32 and yields its chain lock after progress; Java still connects a bounded batch, not Core's exact event loop. |
| No block inventory announcements during IBD (`UpdatedBlockTip`) | Java announced every historical connected block to other peers, feeding a bounded 64-task send queue. A non-suspending debugger logpoint proved entry into this path with IBD=true. Historical announcements now return before queueing or marking announcement history. Orphan processing remains active. |
| Empty GETHEADERS response below active minimum chainwork | Java served historical header ranges, holding the chain monitor while filling ancestry caches. Low-work public tips now receive the empty response before locator/disk traversal. Java does not implement Core's explicit Download permission exemption. |
| In-memory block-index/skip ancestry | Java eagerly read a persistent skip record even when pprev was required. It now decides the jump first. The separate validation failure resolver is seeded from the selected persisted active tip, avoiding a full genesis walk on the first child. Revision invalidation still revokes the proof. |
| Coins cache and disk block cache | Existing UTXO/write-back caching retained. Added an explicit bounded native RocksDB SST cache (128 MiB by default), cached index/filter blocks and Bloom filters for newly written SSTs. This is a RocksDB adaptation, not Core's LevelDB cache allocation. Old SSTs acquire filters through ordinary compaction; no forced rewrite of the existing database. |
| Sequential payload storage and ordered durability checkpoints | Database-owned blk/rev writers reuse append handles. Payload files are forced before synchronous database writes, WAL barriers, write-back checkpoints and close; rollover forces the prior file. Previously every payload append scanned the directory and opened/closed the file. |
| Pruning based on configured mode and file accounting | Java scanned all payload-size metadata even with pruning disabled. Normal/full and manual-only nodes now skip automatic pruning maintenance entirely. Enabled automatic pruning retains its safety ceilings and accounting. |

## Evidence before changing runtime behavior

The existing K:/BitcoinJavaNode/testnet3 database was used, without resets.
`target/ibd-core-policy/testnet-speed-before.log` and its console capture contain
the baseline. A non-suspending validation logpoint observed actual batch sizes
18, 38, 43, 98 and 327. It was removed before the profiler measurement.

JFR recording: `target/ibd-core-policy/speed-before.jfr`, samples exported to
`speed-before-samples.json`. Lifecycle-thread native samples identified
skip-record GETs, FileDescriptor.close0, UTXO MultiGet and pruning-size iteration.
The first connected block required 1,325,691 database GETs. Later measured
intervals spent most processor time in commit, with script execution negligible
for the historical assume-valid range. Empty downloading slots and local peer
closes occurred despite several ready peers.

An intermediate run (`testnet-speed-intermediate.log`) showed input-heavy blocks:
4,355 UTXO misses consumed about 7 seconds of I/O, and active-header prefetch was
the dominant block-index miss caller. A temporary relay logpoint reported
IBD=true at the historical announcement entry; it was removed automatically.

## Controlled offline measurement

Same InitialSyncThroughputTest, 4096 preloaded regtest headers, 512-block input
batches, real consensus connection, file storage and database checkpoint/reopen:

| Build | Connection time | Blocks/s |
| --- | ---: | ---: |
| Before | 6.768 s | 605.2 |
| Sequential writers + pruning/ancestry corrections | 0.742 s | 5521.3 |
| Repeat | 0.707 s | 5794.8 |
| Explicit SST cache, repeat | 0.685 s | 5982.3 |

Logs: speed-offline-before.log, speed-mechanisms-tests.log, speed-relay-tests.log,
speed-final-focused.log under target/ibd-core-policy. The relay test invocation
initially had one fixture failure: a live-announcement test used a genesis-tip
service still in IBD. Its live-tip precondition is now explicit; separate tests
cover suppression during IBD and re-enabling after IBD. Focused repeat passed.

These figures compare deterministic local connection work, not public-network
IBD. Consecutive public testnet ranges contain different transactions and peers;
their throughput cannot establish a controlled speedup over Core.

The native cache setting is `bitcoin.rocksdb-block-cache-mib` (8..16384, default
128). Its budget is additional to Java heap/UTXO and write-back caches.

## Public testnet validation on the existing K: database

The final run used `K:/BitcoinJavaNode/testnet3` without resetting its data.
It advanced from height 678666 to 690613. After the first positive console
sample, the baseline had 92 zero-rate samples out of 135 (68.1%); the final run
had 90 out of 381 (23.6%). The longest consecutive zero runs were six and seven
samples respectively: pauses became less frequent, but were not eliminated or
uniformly shortened. These are approximately one-second console observations,
not matched block-range speed measurements; ranges and peer conditions differ.

During the long final-run pause, approximately 940 bodies were already buffered
and the frontier was available. A live thread dump places the lifecycle worker
in `RocksDB.multiGet` through `prefetchInitialSyncInputs`. Of the lifecycle JFR
top-frame samples, 108 were native MultiGet, seven native Get and two file force;
the baseline's repeated file close/pruning scans no longer dominate. Cold UTXO
reads remain a measured limitation. Later coinbase-heavy sections also showed
historical block-index reads and network latency limiting progress.

The final run did not report `LOCAL_CLOSE` before shutdown. It transitioned
STOPPING to STOPPED in 76 ms and its process exited. Logs and profiles are in
`target/ibd-core-policy/testnet-speed-{before,after}-console.log`,
`testnet-speed-after.log`, `speed-after-platform.txt` and `speed-after.jfr`.

## Follow-up: immutable transaction hashes and bounded cold-input work

Core's [CTransaction](https://github.com/bitcoin/bitcoin/blob/v31.1/src/primitives/transaction.h)
retains txid and witness hash. Java previously serialized and double-hashed every
request. Its immutable Transaction now memoizes both hashes with safe publication;
no witness transaction uses the same cached txid. The regression compares raw
serialization hashes across concurrent readers and attempted mutations of arrays
returned by defensive accessors. Cache lifetime is the transaction object's
lifetime; it introduces no persistent chainstate or reorg invalidation requirement.

The coordinator additionally bounds a connect step to 2048 non-coinbase inputs,
alongside the existing 32-block ceiling. A block larger than this budget is still
processed whole by itself. This is a Java-specific scheduling budget, not a Core
consensus/policy limit, and does not claim Core's exact activation loop. It reduces
the prefetch work placed before the next progress/window-refill opportunity;
single expensive blocks and cold reads can still take seconds.

Prefetch omits inputs referencing output indices actually produced by an earlier
transaction in the ordered batch. These are resolved normally by consensus
processing and committed UTXO updates. Forward references and out-of-range output
indices remain external reads. No coin is fabricated or marked spent by prefetch.
Dependent transactions and a rejected double spend are exercised through the real
validation service, with active tip unchanged on rejection.

All 50 focused tests passed in `speed-prefetch-tests.log`; the coinbase-only
4096-block offline fixture took 0.648 s (6316.3 blocks/s). Against the preceding
0.685 s observation the difference is small and uncontrolled; this is not proof of
an equivalent speedup on input-heavy public testnet. The new whole-block scheduling
regressions exercise 0, 1024 and 4096 inputs per simulated block with moving-window
refill between steps.

The complete follow-up Core-enabled reactor passed: 3915 tests in 357 classes,
zero failures/errors, three skipped (`speed-prefetch-full-core.log`, BUILD
SUCCESS, 5m42s). This includes crash/reopen and snapshot-process crash coverage.
The same isolated 8192-block chain finished with matching hashes: Java 2.656 s,
Core 79.482 s. The previously described coinbase/local-network/timing caveats
still apply. The offline fixture repeated at 0.465 s, illustrating timing variance
rather than establishing a general speed ratio for the new optimizations.

The subsequent public testnet run, on the same existing K: database, reached
709260 and exited normally (STOPPING to STOPPED in 128 ms). After the first
positive sample at 698553, 29 of 144 approximately one-second console observations
were zero (20.1%), with a longest zero run of eight samples. No LOCAL_CLOSE was
reported before shutdown. Log: `testnet-prefetch-after.log` and its console
capture. Compared with the earlier 23.6% this is a different range/peer run, not a
controlled speedup; longest pauses did not improve. Historical index and cold
UTXO access still limit actual progress despite buffered available bodies.

## Remaining architectural limits

No consensus checks were removed. Core still has a fully in-memory block index,
per-peer branch traversal and linked-body download advancement, richer header
presync/peer eviction and a different script verification queue. Received Java
bodies remain buffered until ordered connection. Full-size blocks, cold UTXO
reads, real stalls and checkpoint writes can still produce zero-rate intervals.
The console continues to show actual interval progress, without smoothing away
zero values. This work does not certify complete Core parity or equal public
testnet throughput.

## Core interoperability measurement

The full reactor invocation includes BitcoinCoreMiningRoundTripTest with
`bitcoin.core.binary=C:/Program Files/Bitcoin/daemon/bitcoind.exe` and
`ibd.benchmark.core=true`. An isolated Core generates the same 8192-block regtest
chain served sequentially to an isolated Core receiver and to Java. Final tip
hashes are asserted equal. This run measured Core at 84.618 s (96.8 blocks/s)
and Java at 2.870 s (2854.3 blocks/s); log: speed-full-core.log.

This small coinbase-only localhost scenario is not a benchmark of mainnet or
remote testnet IBD. Core receiver timing uses RPC polling while Java polls its
in-memory tip, and connection setup is included; it establishes interoperability
and this fixture's end-to-end timing, not a general performance ranking. The
offline connection repeat in the same invocation was 0.584 s for 4096 blocks.

## Validation coverage

The full Core-enabled reactor ran 3910 tests across 356 classes (three skipped).
Storage, consensus, chain, p2p, mining and Core interoperability passed, including
15 forced-process crash cases and seven snapshot-process crash cases. That
invocation ended BUILD FAILURE because the newly added moving-window fixture
constructed completions with no source peer, which the stall tracker requires.
The fixture now supplies its simulated source; no production fix was needed.
All 23 coordinator tests then passed in speed-window-repeat.log, BUILD SUCCESS.
The preceding focused storage/relay/connection invocation also passed. Thus
there are no remaining failed checks, but speed-full-core.log itself is not a
green combined-build log. It is retained to make the evidence auditable.

The moving-window regression verifies that a new tail request is submitted
before the next ordered connect step, including when a long downloaded run is
already ready. Other additions cover unused skip-record avoidance, low-work
GETHEADERS without storage traversal, IBD announcement suppression/resumption,
writer ownership/close, record offsets/rotation, checkpoint visibility and reopen.
