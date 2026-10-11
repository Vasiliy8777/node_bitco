# Running node slowdown, 2026-10-10

Process 31804 remained running; no restart or production changes were made.
The latest ten-minute PERF interval inspected was 12:10:45–12:20:41 Bangkok time,
active height 740925 -> 741327, 402 blocks in 595.703 seconds (0.675 blocks/s).

## Evidence

| Metric | Yesterday's warmed interval, heights ~499k–501k | Current interval, height ~741k |
|---|---:|---:|
| Blocks/s | 9.94 | 0.675 |
| Transactions/block | 2368 | 1999 |
| Processor ms/block | 85.5 | 1462.1 |
| RocksDB read ms/key, including cached reads | 0.00954 | 0.52805 |
| Read time / measured processor time | 26.9% | 95.8% |
| Java coin-cache hit ratio, including absent coins | 52.8% | 59.3% |

These are different historical blocks, not identical-block benchmark results.
Transaction count is not serialized block size. Nevertheless, the lower average
transaction count and dominant read time do not support simple block-size or
CPU-validation growth as the primary cause. For example, block 741289 had 604
transactions but took 5644.7 ms to process; UTXO reads accounted for 5216.4 ms.

All 100 current pipeline samples had the frontier already available. Around
1020 downloaded blocks were buffered, with only 1–3 downloads pending. Ten
peers were ready. Network throughput therefore exceeded validation throughput
in this interval; a single active download peer is expected when the window is
full and only a few tail slots become available.

The validation thread dump shows native RocksDB MultiGet through
RocksDbDatabase.getAll -> RocksDbUtxoStore.findAll -> UtxoOverlay.prefetch ->
BlockConnectChangesBuilder.prefetchBlockInputs. This is the normal per-block
resolution of inputs, not disabled speculative look-ahead. The phase label
`reorgPrepare` includes preparing an ordinary active-chain extension; it does
not establish repeated actual chain reorganizations.

During a 45-second JFR recording, 98 of 145 lifecycle samples had native
RocksDB MultiGet as their top frame. GC had 19 events (14 G1New, 5 concurrent
G1Old cycles), with 0.233 seconds total reported pauses (~0.52% of the recording).
GC is not the dominant observed cause. Heap maximum remains 4 GiB; available
physical memory at inspection was approximately 5.9 GiB.

Windows initially reported K: at 101% disk time, 131 reads/s, 0.65 MB/s; a later
sample reported 71%, 98 reads/s, 0.52 MB/s. The raw counter interval contained
7997 reads over about 69.54 seconds. Correcting the 32-bit cumulative read-time
counter wrap gives about 8.09 ms per physical read. Low bandwidth with high disk
activity is consistent with random HDD reads.

The latest RocksDB stats confirm its 4 GiB native cache is full, with approximately
3.67 GiB data blocks plus index/filter blocks. SST files total about 5.46 GiB;
this includes multiple database namespaces and is not the isolated UTXO size.
Native cache blocks are not directly comparable to compressed SST file bytes.
The last 600-second stats interval has zero write stalls and no pending
compaction bytes, but includes ~1.06 GiB compaction reads and ~1.13 GiB writes,
which can compete with foreground reads.

## Conclusion

The present slowdown is predominantly a storage/cache-working-set limitation:
UTXO misses require random HDD reads. Growth and changing access patterns of
the active coin set are a plausible reason the warmed historical range no longer
represents current speed; exact native hit/miss deltas were not captured.
There is no evidence in this measurement of BIP324 processing, missing network
blocks, GC pauses or unusually high transaction count dominating the slowdown.
This does not establish that all implementation paths are optimal.

Moving chainstate to the existing SSD would directly address physical read
latency. Increasing caches further with only ~6 GiB currently available should
be evaluated against OS page-cache and memory pressure, rather than assumed to
solve every cold read. Neither change was performed for this analysis.

Artifacts: `target/ibd-slow-20261010-summary.json`,
`target/ibd-slow-20261010-threads.txt`, `target/ibd-slow-20261010.jfr`,
`target/ibd-slow-20261010-events.json`, live RPC samples and before/after disk
counters under the same filename prefix.
