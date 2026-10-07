# Coherent bounded block metadata cache

The preceding public testnet comparison covered the same 10,000 blocks at
heights (945000, 955000]. Java took 127.614 seconds versus Core's 68.877 seconds.
The namespace report attributed 19.834 seconds to block positions, 13.345 to
undo positions, 14.691 to skip ancestry, 8.159 to availability and 6.210 to
failure metadata. These are accumulated logical-get times, not physical disk
service times or additive independent processor phases.

## Implementation

Finish the interrupted metadata-cache patch: RocksDbDatabase owns a 65,536-entry
LRU guarded by its existing monitor. Only 33-byte hash keys in BLOCK, UNDO,
BLOCK_FAILURE, BLOCK_AVAILABILITY and BLOCK_SKIP_INDEX qualify; non-null values
larger than 64 bytes are excluded. Legacy inline payloads are not retained.
Keys and values are copied; both present and absent records are cached.

Height-hint prefetch reads body, undo, failure and availability records alongside each primary
index in existing chunks of at most 256 indexes. The scan remains bounded to
2048 height entries. Prefetched metadata does not prove ancestry, body validity
or eligibility; existing checks continue to read coherent storage state.

The interrupted patch also eagerly read a skip record for every hinted index.
The first live observation showed 395 skip gets for 409 processed blocks, unlike
the earlier selective ancestor traversal. Sequential prepared paths follow parent
hashes, so the final patch drops eager skip prefetch while retaining coherent
on-demand skip caching. A regression forbids skip reads during height warming.

Direct successful native writes publish values or missing records. Successful
native batches publish in operation order, including namespace deletion. Writes
held in the chainstate write-back batch invalidate metadata; the pending overlay
remains authoritative. A successful durable flush publishes the batch before
its disposal. Unsubmitted batches publish nothing. Reopening starts a fresh cache.
An oversized replacement invalidates the old small record before bypassing cache.
No WAL, payload flush, checkpoint, pruning or consensus rule is removed.

ReadCacheStats exposes native keys read by get/getAll and metadata hits separately
from the existing logical-get counters. Discarded asynchronous native warming and
iterator reads are outside this counter; it is not a total disk-I/O counter.

## Verification

The real-RocksDB regression proves that 128 repeated single and mixed batch reads
of committed values and cached misses perform no additional native reads. It
also verifies write-back read-your-writes, publication after flush, ordered range
deletion and oversized replacements. Existing regressions cover abandoned batches,
defensive copies, direct deletion, bounded eviction and database reopening.

`target/ibd-core-policy/metadata-cache-resume-full.log` completed all reactor
modules except one app test: StratumIntegrationTest inherited a mainnet payout
address from BITCOIN_MINING_PAYOUT_ADDRESS while using regtest. Its isolated rerun
with that process-local variable cleared passed all six Stratum tests:
`metadata-cache-resume-stratum-clean-env.log`. The metadata tests passed all three
cases. This initial full run is not reported as BUILD SUCCESS.

The final full reactor with the process-local payout variable cleared passed:
`metadata-cache-resume-final-full.log`, BUILD SUCCESS in 2m14s: 3947 tests,
zero failures/errors, eight skipped. The added
height-warming/native-read regression passed separately in
`metadata-cache-final-native-read-regressions.log`. Production code was unchanged
between these successful checks.

`metadata-cache-final-core-integration.log` explicitly enables the installed
Core binary and passes the two actual isolated-daemon tests in
BitcoinCoreMiningRoundTripTest (testnet4 genesis and mining/relay/reconnect).
Its third, opt-in throughput benchmark is skipped. The full reactor's separate
external-daemon tests requiring opt-in parameters are not counted as executed.

An IDE debugger launch of the short cache test ended before events could be
drained. No debugger values were captured or used as evidence. Agent logpoints
were removed; the real native-read counters and assertions provide the evidence.

## New public comparison

Repeat Core and Java sequentially on their retained testnet3 databases on K,
using the common interval (991000, 1001000] and the existing RPC sampler. Startup,
header loading, progression below the first boundary and shutdown are excluded.
Artifact prefix: `target/ibd-core-policy/testnet-final-`.
Both runs finished, their end hashes match, and both processes are verified
absent. Java shutdown logged STOPPED. The existing databases were retained.

| Same 10,000 blocks / 106,629 transactions | Core 31.1 | Final Java |
| --- | ---: | ---: |
| RPC-interpolated elapsed seconds | 115.764 | 73.779 |
| Connected blocks/second | 86.383 | 135.541 |
| Median sampled interval rate | 124.867 | 144.827 |
| Sampled intervals without tip progress | 40 | 1 |
| Seconds represented by those intervals | 40.274 | 1.006 |
| Longest sampled no-progress span | 25.166 | 1.006 |

Boundary 991000: `000000000028223865de3ee8944536f16e1ac24f0b4a740b85e09f5aedabc0aa`.
Matching end hash at 1001000:
`000000000050e7682000ef0d8c07dfe92b801a7f6369b6bb1986afb49e944d5e`.
Core's exact boundary UpdateTip timestamps independently give 115.599775 s.
The transaction count is the difference between Core's exact cumulative
UpdateTip counters, 11624640 minus 11518011; RPC was already closed after
Core's automatic stop-at-height shutdown.

Java achieved 156.91% of Core's observed end-to-end speed in this run. This meets
the speed condition for this particular historical interval, not general speed
or consensus parity. The two nodes used public peers, sequentially, on the same
machine and K disk. Native/coin cache byte allocations and peer pools remain
different, and the OS cache was not cleared. Java used the existing production
consensus/assumevalid policies and cache limits; Core retained its defaults.
No tests, compilation, debugger logpoints or second measured node ran inside
either timed interval. The header tips were already available above the interval.

## What the speed result establishes

Core's three largest exact inter-tip gaps were 25.673 s at 991164, 10.509 s at
991165 and 7.025 s at 991146. Network logging directly associates those hashes
with requests at 12:15:55 UTC and receipts at 12:16:29, 12:16:39 and 12:16:03:
the tip updates follow body receipt by approximately 2, 20 and 21 ms respectively.
These are demonstrated public-peer delivery delays, not evidence of slow Core
validation. Its 10,000 logged Connect block stages total only 18.63164 s and do
not include all reception/storage costs. Subtracting sampled no-progress spans
does not isolate either node's processor or provide a controlled speed ratio.

The broad speed result therefore cannot be attributed solely to the cache or
the JVM launch change. Java still incurs synchronous cold primary-index reads,
transaction-heavy UTXO costs and frontier gaps. Its warmup included a roughly
130-second header-response wait and a missing-body gap near 990810, all below
the first timing boundary. Header failover completed at 19:20:46 local time;
manual RPC disconnection of that stale peer occurred afterward at 19:20:57.
The Thread.print startup snapshot documents the wait in HeaderSynchronizer.

Within a diagnostic Java interval of 391 blocks, selective skip reads were
42 rather than the interrupted version's roughly one eager read per block.
These are different intervals, so they verify the intended prefetch behavior
rather than a controlled throughput improvement. Native-key/cache-hit counters
are now present in IBD METADATA CACHE console records.

Main artifacts: `testnet-final-comparison-summary.json`, both
`testnet-final-*-samples.jsonl` files, `testnet-final-core-network.log`,
`testnet-final-java-console.log`, `testnet-final-details.json`, and the
`measure-testnet-final.ps1` / `analyze-testnet-final.ps1` scripts in
`target/ibd-core-policy/`. No old baseline artifacts were overwritten.

The planned (966000, 976000] range was abandoned after Java startup revealed an
already persisted tip at 973840. Those preliminary artifacts are retained. A
subsequent Java run over (977000, 987000] used the interrupted eager-skip version
and the IntelliJ Spring configuration's `-XX:TieredStopAtLevel=1`. That flag was
observed in the actual process command line, limits compilation to tier 1, and
is omitted from the final direct Java launch. It cannot establish the separate
contribution of cache changes versus JVM compilation settings. No database was
rewound or deleted to obtain a convenient measurement interval.

Java processes are stopped through their enabled Spring application admin JMX
shutdown method, using local JDK attach only to discover the connector address.
Shutdown reaches STOPPED; no forced process termination is used.
