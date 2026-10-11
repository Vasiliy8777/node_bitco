# Larger UTXO and RocksDB caches: runtime check

Mainnet now defaults to `bitcoin.rocksdb-block-cache-mib=4096` and
`bitcoin.utxo-read-cache-entries=1000000`. Environment overrides are
`BITCOIN_ROCKSDB_BLOCK_CACHE_MIB` and `BITCOIN_UTXO_READ_CACHE_ENTRIES`.
Other profiles and existing Java constructors retain the 500000-entry default.
Zero disables the Java read cache; negative capacities are rejected before
initialization. Snapshot/background stores retain their existing cache policy.
No persistent data was moved or converted.

The previous process had already shut down at 16:23:08. The new process,
PID 31804, started at 16:28:24 with mainnet,gpu profiles and was left running.
RocksDB LOG confirms capacity 4294967296 bytes; runtime IBD telemetry confirms
the Java cache reached 1000000/1000000 entries.

## Observed results

| Metric | Previous process, last ~3 minutes | New process, ~4 minutes |
|---|---:|---:|
| Active height | 499053 -> 500828 | 500866 -> 502215 |
| Measured seconds | 177.994 | 237.988 |
| Connected blocks/s | 9.97 | 5.67 |
| Java read-cache hit ratio | 52.72% | 47.16% |
| RocksDB read telemetry, ms/1000 transactions | 9.77 | 18.56 |

After excluding the first 60 seconds of new PERF samples, the new process
averaged 6.61 blocks/s, a 49.52% Java cache hit ratio and 18.34 ms of RocksDB
read telemetry per 1000 transactions. Hit ratios include cached absent coins;
RocksDB timing includes cache-served reads and monitor waits, not only physical
disk accesses. Different historical blocks and peer sets prevent attributing
these differences solely to cache size.

**A throughput improvement was not demonstrated in this measurement.** The new
process had substantial frontier gaps: hundreds of later blocks were buffered
while an earlier block was still outstanding from a remote peer. During one
such gap Windows reported zero reads and zero active time on the K: disk.
Larger caches cannot resolve this network/ordered-processing delay. Later
intervals also contain database write/commit costs and variable UTXO read costs.

All 80 chain RPC and 80 peer RPC calls succeeded, with a chain RPC median of
13.64 ms and nine live peers at the end. There were no relay-overload errors.

Process working set subsequently reached about 8.96 GiB, private bytes 9.11 GiB;
Windows still reported approximately 9.13 GiB available RAM. The heap maximum
remains 4 GiB. In a 180-second JFR recording, GC reported 262 collections
(210 G1New, 52 G1Old concurrent cycles), about 4.01 seconds total GC pauses,
maximum 60.5 ms, and no G1Full collections. Recent post-GC heap occupancy was
around 3.1 GiB. This establishes increased memory cost and limited heap
headroom, without an observed full-GC failure; increasing Java cache further is
not justified by this measurement.

## Validation and artifacts

39 tests passed: NodeConfigurationTest, NodeValidationServiceTest,
RocksDbUtxoStoreTest and BlockPrunerTest. See
`target/ibd-larger-cache-tests.log`.

RPC samples: `target/ibd-larger-cache.jsonl`; PERF comparison:
`target/ibd-larger-cache-summary.json`; profile:
`target/ibd-larger-cache.jfr`; GC events:
`target/ibd-larger-cache-gc.json`. The application log remains
`target/mainnet-node.log`.

The larger cache settings are installed as requested. Their benefit on future
cold UTXO ranges remains unproven; a controlled replay with the same blocks and
local block availability would be needed to isolate cache effects.
