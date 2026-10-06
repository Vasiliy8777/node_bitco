# Bounded UTXO cache hints, 2026-10-06

## Motivation and Core boundaries

The preceding public run separated a 27-second wait for block 721089 from a
later processing bottleneck: around height 721172, 91961 UTXO reads consumed
4385 ms in one telemetry interval while 1022 bodies were available. Changing
GETDATA limits or ordinary download timeouts would not fix those coin reads.
Core's 16 requests per peer, 1024 window and window-stall gates remain unchanged.
Java still advances its window from ordered connection, whereas Core tracks
linked received bodies separately; this remains an architectural difference.

This change adds a Java-specific bounded read hint, rather than claiming an
identical Core prefetch algorithm. Core's cache/validation gates remain the
reference; all coin values used for validation still come from the ordinary
chainstate view and its committed changes.

## Mechanism and safety

Before connecting a step, the coordinator offers up to four already received
successor blocks for warming. Collection stops after 8192 external input points.
One daemon worker handles a running hint and one queued hint; new hints replace
the oldest queued job. Idle threads expire after five seconds; close interrupts
the worker and drops queued jobs.

The UTXO store omits points already in its Java read cache. RocksDB reads at most
512 keys per native call, skipping write-back-touched points. Native reads occur
outside the database monitor, so they can overlap commits and normal reads.
Results are discarded: they never populate the Java coins cache, mark spentness,
decide missing inputs, or prove validity. A namespace switch or reorg can make a
hint irrelevant, but cannot make its discarded coin values authoritative.
RocksDB's internal SST/block-cache version handling remains responsible for
native cache coherence. A lifetime read lease protects native resources;
database disposal takes the exclusive lease after stopping new hints. A hint
does not need the database monitor to release its lease, avoiding a close deadlock.

Hint failures are logged; normal validation reads remain authoritative.
`bitcoin.ibd-utxo-prefetch` enables the mechanism (default true). It can be
disabled to compare machines or diagnose I/O competition. `IBD UTXO PREFETCH`
logs cumulative hint keys/time separately from normal consensus read telemetry.

The pinned RocksDB 10.2.1 [JNI implementation](https://github.com/facebook/rocksdb/blob/v10.2.1/java/rocksjni/rocksjni.cc)
already uses the optimized native MultiGet API for `multiGetAsList`; switching
to direct buffers was therefore not assumed to eliminate this bottleneck.

## Tests and matched-chain measurement

Focused validation passed 62 tests, covering bounded worker queue/replacement,
coin-cache non-publication across spend/namespace changes, native hints during
writes/close, normal chain processing, configuration and moving-window cases.
Log: `target/ibd-core-policy/speed-warm-tests.log`.

`InitialSyncUtxoThroughputTest` is opt-in (`ibd.benchmark.utxo=true`). Each run
starts from identical 262144 synthetic non-coinbase OP_TRUE coins and the same
64-block chain spending 65536 inputs. Each database is seeded separately, SSTs
are flushed, and it is reopened with a cold native cache of 8 MiB. The filesystem
cache is not cleared. Two rounds reverse the enabled/disabled order. Final tip
and representative spent/unspent coins match; no timing assertion is used.

| Round | Hints | Seconds | Blocks/s | Native hint keys |
| --- | --- | --- | --- | --- |
| 0 | off | 0.660 | 96.9 | 0 |
| 0 | on | 0.460 | 139.2 | 88064 |
| 1 | on | 0.407 | 157.3 | 83968 |
| 1 | off | 0.476 | 134.3 | 0 |

These measurements show 15–30% less elapsed connection time in this fixture,
not a public-testnet or Core speed guarantee. Hints perform extra reads and can
compete for I/O/cache on other machines. Log: `speed-warm-ab-repeat.log`. The
initial fixture passed no spend transaction to CoinbaseBuilder and therefore
constructed a wrong witness commitment; the node correctly rejected it. The
fixture was corrected, without relaxing validation (`speed-warm-ab.log` retains
the failed attempt).

The full Core-enabled reactor ran 3920 tests in 359 classes, with zero assertion
failures, three skips and one setup error (`speed-warm-full-core.log`). The error
was the Core CLI's 10-second timeout generating a 512-block source batch before
Java IBD started. Crash/reopen, snapshots, all storage/chain/p2p tests and the
other Core interoperability cases passed. The fixture now gives only bulk
`generatetoaddress` calls 60 seconds and waits for timed-out CLI termination;
ordinary polling calls and production peer timeouts are unchanged. The full log
remains a failed-build log, rather than being described as a green full run.

The three Core tests then passed in `speed-warm-core-repeat.log` (BUILD SUCCESS).
The identical 8192-block coinbase-only regtest chain reached the same tip:
Java 2.821 s, reference Core 82.396 s. This localhost fixture, setup/polling
differences and tiny blocks do not establish equal public-network throughput.
The full-run setup failure has no remaining failed test after this targeted repeat;
production code was not changed between the full invocation and the repeat.

The full-run matched-UTXO repeats were 0.536/0.357 s (off/on) and
0.355/2.858 s (on/off). The large disabled-run outlier shows machine/cache timing
variance and must not be used to promise a multi-fold real-world speedup.

## Public run and shared index cache

The warm-only public run used the existing K: database without resets. In
`testnet-warm-after-console.log`, 201 samples after the first positive reading
had two zero samples (one two-sample series), from 725257 to 743750. Shutdown
transition took 81 ms and the process exited. Different blocks, peers and cache
conditions mean the apparent improvement over the previous 27-second frontier
wait is not a matched speed/stability comparison.

Remaining telemetry showed historical index I/O despite ready bodies. Inspection
found separate StoredBlockIndexLookup caches in validation and sync infrastructure.
Production wiring now shares the bounded immutable index/skip cache, so indices
materialized for the download path are also available to block validation and
assume-valid ancestry. This adapts Core's shared global block index; Java still
uses a bounded cache rather than loading the entire index. Mutable failure and
validation-status stores and resolver revision invalidation are unchanged.
Standalone constructors retain their independent lookup for existing callers.
A Spring configuration regression asserts the production lookup identity.

After sharing the index cache, 50 selected tests passed with one opt-in benchmark
skipped (`speed-shared-index-tests.log`), including configuration, moving-window
processing, dependent spends, ancestry equivalence and Core roundtrip/reorg. The
3920-test full invocation above predates this final wiring change; the targeted
tests are its verification, not a second green full-reactor claim.

Final public run (`testnet-shared-warm-after.log` and console capture) reached
758794. Among 146 one-second observations after first progress at 743898, three
were zero (2.1%); longest series was two samples. The process exited normally,
with STOPPING to STOPPED in 4.105 s. Throughput was generally 100–110 blocks/s on
this mostly simple range. Sampled lookup misses now point to download-path
materialization rather than repeated assume-valid ancestry. Normal block-index
store reads, cold inputs and network frontier waits remain possible. Neither
this short run nor the synthetic benchmark proves complete Core speed/stability
parity on all historical ranges.
