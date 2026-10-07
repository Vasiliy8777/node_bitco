# Public testnet3: Java node versus Bitcoin Core 31.1

## Result

Measured the same canonical testnet3 interval `(945000, 955000]`: 10000 blocks
and 186329 transactions. Both nodes received these block bodies through public
P2P connections. These are real historical testnet3 blocks, not regtest fixtures.

| Measurement | Bitcoin Core 31.1.0 | Current Java node |
| --- | ---: | ---: |
| Elapsed time for the common interval | 68.877 s | 127.614 s |
| Connected blocks/s | 145.186 | 78.361 |
| Median sampled interval rate | 170.445 | 84.036 |
| Sampled intervals overlapping the window | 68 | 122 |
| Intervals with unchanged sampled tip | 2 | 5 |
| Time represented by unchanged-tip intervals | 2.024 s (2.94%) | 5.021 s (3.93%) |
| Longest consecutive unchanged-tip observation | 1.013 s | 2.007 s |

Core was **1.853 times faster** in this run. Java achieved **53.97% of Core's
observed throughput**. The requested condition of at least Core's testnet speed
has not been achieved in this measurement.

Boundary hashes matched:

- 945000: `00000000000295e325dbd182869d8d200dc8008bb973ad2a1c8e81c37beb13a7`
- 955000: `00000000000236c92d238857e6a868ddfa8af57cfb22aec93a04bdd11e4025d4`

Core's exact UpdateTip timestamps at the boundaries independently give 68.863347 s,
very close to the common RPC sampling estimate above.

## Conditions and preparation

- Same Windows machine: i5-13600KF, 20 logical processors, approximately 32 GiB RAM.
- Both chain databases on K, a SATA WDC WD60EZAZ-00SF3B0 disk, NTFS.
- Java: existing `K:/BitcoinJavaNode/testnet3`, normal `testnet` Spring profile.
- Core: isolated `K:/BitcoinCoreSpeedCheck/testnet3`, `-testnet`, RPC 18436.
- Runs were sequential; Core completed shutdown before Java was started.
- Both used their existing/default consensus and assumevalid parameters.
- Core used default cache allocation (1024 MiB dbcache in this binary). Startup
  logged 1014 MiB UTXO cache, 8 MiB chainstate DB cache and 2 MiB block-index DB cache.
- Java used its existing 128 MiB native RocksDB block cache, 500000-entry positive
  UTXO cache, 131072-entry immutable index cache and write-back limits of 64 MiB,
  250000 operations or 30 seconds. These allocations are not normalized by bytes.
- Core network/bench logging was enabled. Java used its existing IBD diagnostics;
  no new debugger logpoints or suspended breakpoints were added.
- Core had 3 connected serving peers in the measurement, Java approximately 8–9.
  Two working addresses from Java's known pool were supplied to Core through
  `addnode ... onetry`; automatic discovery supplied its third peer. Java retained
  its normal persisted address manager and 8+2 outbound targets. Peer identity,
  role and instantaneous latency were not identical between runs.

The Core DB was first populated from Java's already saved `blk*.dat` through
standard `-loadblock`. This preparation used normal Core validation and is
excluded from measured time. Imported bodies reached height 943685, so the
measurement starts above that height. `-stopatheight` can advance past the requested
height during shutdown; its resulting preparation tip was not used as a timing
boundary. Both RPC samplers' first successful sample was at active height 943685.

Java already had approximately 5.157 million headers. Initially Core only had
headers up to the imported bodies and spent time acquiring peers and entering
headers presynchronization; that delay cannot fairly be compared with Java's
preloaded headers. To establish equivalent header availability, the Java database
was opened **read-only** through RocksDB JNI, and genuine serialized headers were
exported. The exporter followed the exact best-header hash backward, resolved
height collisions by hash, verified every SHA256d/parent link down to genesis,
then submitted the extension to Core through its validating `submitheader` RPC.
No artificial empty blocks were submitted, and no Java DB records were changed.

Verified exported header tip: height 5157002,
`00000000004e419fff8a56353f019ed7259fd96f2a13693d5a9a276e25a8983a`.
Export verification took 8.276 s; export plus Core header preparation 35.438 s.
Network activity was disabled during header preparation and reenabled afterward.
Block downloads during the timed interval used normal P2P scheduling.

`submitheader` checks the candidate header through Core's header-validation path:
[Core mining RPC implementation](https://github.com/bitcoin/bitcoin/blob/v31.1/src/rpc/mining.cpp).
No minimum-chainwork, difficulty, PoW, signature-policy or assumevalid override
was applied to the measured node runs.

Preparing the databases reads files and warms the OS cache. OS caches were not
forcibly cleared. Cache allocation, peer history, remote throughput and run order
remain observational differences. Therefore 1.853 is the ratio for this run,
not a universal performance ratio or proof of maximum attainable Core throughput.
It nevertheless demonstrates a substantial gap on an identical real block sequence.

## Log analysis

### Repeated metadata reads and ancestry

Aggregated Java performance windows wholly inside the common height interval
cover 9300 blocks and 179247 transactions. Time below is accumulated instrumented
database-get time, which includes read-your-writes lookup and native access;
it is not a measurement of physical disk service time alone.

| Java namespace | Keys resolved | Accumulated get time |
| --- | ---: | ---: |
| Block body positions | 37195 | 19.834 s |
| Undo positions | 18600 | 13.345 s |
| Skip ancestry | 1093 | 14.691 s |
| UTXO | 139325 | 14.530 s |
| Availability | 9300 | 8.159 s |
| Block failure metadata | 9301 | 6.210 s |
| Primary immutable block index | 9640 | 0.859 s |

The main primary index reads were relatively cheap after the sequential header
export warmed their pages. The remaining metadata and skip reads were expensive.
For example, the 16:22:31.580 local-time interval processed 736 blocks in about
5.1 s, and recorded 4837.4 ms of database gets, including 1862.2 ms for body
positions, 1314.5 ms for undo, 626.2 ms for availability and 605.0 ms for failure
metadata. The actual block-connection validation stage was much cheaper than
the complete reception/storage/connection path.

Core maintains its CBlockIndex tree, parent/skip pointers and status information
in memory, reducing lookup work on the synchronization/activation path:
[Core block manager](https://github.com/bitcoin/bitcoin/blob/v31.1/src/node/blockstorage.h),
[Core chain index](https://github.com/bitcoin/bitcoin/blob/v31.1/src/chain.h).
This is a concrete architectural difference from Java's bounded index lookup
plus separate persistent metadata namespaces.

Core's bench log contains exactly 10000 `Connect block` records following updates
for heights 945001–955000. Their accumulated time is 15.42332 s, or 1.542332 ms/block.
This Core stage excludes some earlier network reception and body-storage work,
so it must not be equated directly with Java's broader processor timer.

### Missing frontier blocks and processing-bound intervals

Within the measured Java interval the window stall detector disconnected peers
at frontier heights 945800 (16:21:07.014) and 947617 (16:21:35.958). Their stall ages
were 2.256 and 2.081 s against the 2 s window timeout. A stall at 944686 occurred
before the common measurement boundary and is excluded from the conclusion about
stalls within the measured range. Other BIP324 EOF/read failures also caused peer
replacement. A larger ready-peer pool therefore did not eliminate frontier waits.

Java pipeline samples also show hundreds of downloaded blocks waiting in the
buffer while the frontier is available. Thus the run has both network-gap and
processing/storage limitations. Adding peers alone cannot remove the measured
metadata costs. On low-transaction intervals Java reached approximately 140–145
blocks/s; transaction-heavy intervals and waits lowered the whole-range average.

Both runs used standard assumevalid with the same configured anchor; Java logged
zero script-verification time in these historical windows. Core's 15 extra script
threads should not be presented as the demonstrated cause of the speed ratio
in this already buried historical interval. The relevant evidence here is metadata,
ancestry, UTXO/transaction processing, write-back behavior and peer/frontier waits.

## Measurement method and artifacts

Loopback `getblockchaininfo` samples use a monotonic stopwatch, aiming for one
sample per second. Actual RPC duration is included. Linear interpolation locates
the two fixed height boundaries; both nodes use the same procedure. Startup,
header preparation, warmup, data above the final height and shutdown are excluded.
The zero-progress values describe sampled intervals; they are not a precise
measurement of every internal pause, since RPC locking and batch activation can
hide or shift a short stall.

Artifacts in `target/ibd-core-policy/`:

- `measure-testnet.ps1`, `analyze-testnet.ps1`
- `PrepareCoreHeaders.java`, `testnet-core-header-preparation.log`
- `testnet-paired-core-samples.jsonl`, `testnet-paired-java-samples.jsonl`
- `testnet-paired-comparison-summary.json`
- `testnet-paired-core-network.log`, `testnet-paired-java-console.log`
- `testnet-paired-core-txstats.json`
- `testnet-paired-core-connect-phases.json`, `testnet-paired-java-namespace-summary.json`
- endpoint, peer snapshots and final process-memory JSON files for both nodes.

Both processes were stopped and verified absent. Existing Java chain data and
the user's mainnet Core database were retained. The isolated Core testnet DB is
also retained for future reproducible comparisons. Original Java source/configuration
was not changed for this measurement.

The principal optimization targets supported by this run are fewer repeated
body/undo/status lookups, more complete in-memory ancestry/status state with correct
publication/invalidation, and lower UTXO/write-back overhead on transaction-heavy
blocks. Any such optimization must preserve reception validation, atomic activation,
reorganization, pruning and restart semantics; this measurement does not certify
full consensus or policy parity.
