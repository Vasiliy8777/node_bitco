# Speed check: Java node and Bitcoin Core 31.1

## Public testnet measurement

Started the current compiled Java node with `testnet` profile and existing
`K:/BitcoinJavaNode/testnet3` database. No rebuild ran while this node was active.
PID 103028 was stopped normally after measurement; shutdown reached STOPPED.

Sampled `getblockchaininfo` over loopback RPC with a monotonic stopwatch. The
sampler aimed for one-second intervals; actual intervals include RPC latency,
so throughput uses measured elapsed time rather than assuming exact seconds.
This measures connected active-tip progress, not received headers or bodies.

| Metric | Value |
| --- | ---: |
| First measured active height | 922808 |
| Last measured active height | 929038 |
| Connected blocks | 6230 |
| Elapsed measurement | 149.319 s |
| Throughput (height delta / time) | 41.723 blocks/s |
| Median interval throughput | 33.078 blocks/s |
| RPC samples / progress intervals | 139 / 138 |
| Intervals with unchanged tip | 19 (13.8%) |
| Longest consecutive unchanged-tip observation span | 10.099 s |

These are observation intervals, not a precise count of internal pauses: batch
activation and RPC lock latency affect the sampling. Shutdown progress is excluded.
The first sample follows startup and initial peer connection, so this is a
running-sync measurement, not time from process start. The final shutdown log
shows a higher tip; it must not be substituted for the measured final sample.

Artifacts in `target/ibd-core-policy/`:

- `speed-check-testnet-20261007-rpc.csv`
- `speed-check-testnet-20261007-summary.json`
- `speed-check-testnet-20261007-console.log`

## What caused slow intervals

At 15:30:22 local time the next missing block was 928581, assigned to
109.236.57.34. Buffer contained 578 later blocks; at 15:30:27 it contained 802,
with zero processed blocks. At 15:30:30 the window stall detector disconnected
that peer after its 2.066-second stall age exceeded the 2-second window timeout.
The complete observed pause includes time before the full-window stall condition
was reached and recovery; the two-second timer is not a universal two-second
limit on every missing block.

Later intervals were processing-bound. At 15:30:42 the buffer held 1011 blocks
and the frontier was available. The 15:30:46 performance sample processed 73
blocks / 26264 transactions at 14.5 blocks/s / 5219 transactions/s. Other intervals
had much lower transaction counts, so their blocks/s are not directly comparable.

At 15:30:11 the 254-block diagnostic interval spent 3813 ms in 250 primary index
gets, 1434 ms in 12736 UTXO gets, 674 ms in 508 undo gets and 679 ms in 1020 block
metadata gets. At 15:30:56 just 11 skip-index gets accumulated 2537 ms. These
counters support further investigation of cold metadata reads and processing;
they do not establish the physical disk as the sole root cause. The zero-speed
intervals have both missing-frontier and processing components.

## Paired localhost benchmark

Ran the existing opt-in `BitcoinCoreMiningRoundTripTest#measuresInitialSyncFromCore`
against installed Bitcoin Core daemon v31.1.0. Both receivers start with fresh
isolated databases and receive the same 8192 coinbase-only regtest blocks from
one Core source, sequentially. Final tip hashes are asserted equal.

Found and corrected an important benchmark bias: Core regtest enables full
block-index consistency assertions by default. Public networks disable these
diagnostic assertions. The performance fixture now explicitly uses
`-checkblockindex=0` for source and Core receiver. Consensus validation is retained;
production node configuration and other Core integration fixtures are unchanged.
`-Dibd.benchmark.core.checkblockindex=1` reproduces the prior diagnostic setting.

Official references:
[regtest defaults](https://github.com/bitcoin/bitcoin/blob/v31.1/src/kernel/chainparams.cpp)
and [checkblockindex option](https://github.com/bitcoin/bitcoin/blob/v31.1/src/init.cpp).

| Receiver | Seconds | Blocks/s |
| --- | ---: | ---: |
| Bitcoin Core 31.1, checkblockindex=0 | 7.920 | 1034.3 |
| Current Java node | 2.526 | 3242.8 |

Log: `target/ibd-core-policy/speed-check-paired-core-public-defaults-20261007.log`.
BUILD SUCCESS: one selected benchmark, zero failures/errors/skips.

The initial uncorrected fixture produced Core 24.616 s / 332.8 blocks/s and Java
3.230 s / 2536.2 blocks/s. Its assertions completed, but JUnit cleanup failed on
a temporarily locked RPC output file on Windows; that run is not a passing test.
The corrected run completed including cleanup.

Reproduce the corrected run while the production node is stopped:

```powershell
.\mvnw.cmd -pl app -am toolchains:toolchain compiler:compile compiler:testCompile surefire:test '-Dtest=BitcoinCoreMiningRoundTripTest#measuresInitialSyncFromCore' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dibd.benchmark.core=true' '-Dbitcoin.core.binary=C:/Program Files/Bitcoin/daemon/bitcoind.exe'
```

## Interpretation

In this small localhost fixture Java is faster. It does not establish a general
3.1x speed advantage: it has coinbase-only blocks, tiny fresh chainstate, sequential
run/cache effects, different tip polling mechanisms (Core CLI RPC vs Java in-memory
tip), and excludes final shutdown/durable flush time. Databases are in the temporary
directory on C, whereas the live Java testnet database is on K. It does not model
public-peer latency, large historical indexes, spent inputs or signature-heavy blocks.

The existing `K:/Bitcoin` Core database is mainnet, not a matching testnet3
chainstate. It was not launched or modified. No Core measurement for testnet heights
922808–929038 is available. Therefore real testnet speed parity or a percentage gap
cannot be established from these runs. An equivalent test would require matching
starting chainstate/height, block sequence, storage/cache and validation settings,
and preferably a common local source to separate network variation from processing.
