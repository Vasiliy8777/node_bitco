# IBD runtime fixes and restart verification, 2026-10-09

## Changes

- `RocksDbDatabase.getAll` captures pending writes, metadata-cache values and a
  native RocksDB snapshot under the database monitor, then performs native reads
  outside that monitor in groups of 256 keys. A lifetime read lock protects the
  native database and snapshot from concurrent disposal. Namespace versions
  prevent stale read results from repopulating the metadata cache after writes.
  WAL durability and chain validation remain unchanged.
- Mainnet defaults to a 2 GiB native RocksDB cache, separate from the 4 GiB JVM
  heap. Speculative UTXO prefetch defaults to disabled on this HDD installation.
  The setting now controls both asynchronous look-ahead and whole-batch prefetch;
  enabled whole-batch prefetch is limited to 8192 external inputs. Normal block
  input validation still resolves every required coin.
- Relay ignores transaction inventory and transaction bodies during IBD before
  allocating queue tasks. It postpones mempool requests, inventory broadcasts
  and fee filters, and coalesces periodic ticks. Block/header handling remains
  available. Local queue overload has its own close reason and does not classify
  an honest peer as a protocol violator.
- Connections retain their remote endpoint after close. Peer removal happens
  before discouragement. RPC serializes an atomic snapshot of peer/role pairs,
  avoiding removal races. Never-connected peers have no endpoint.
- `getblockchaininfo` reads the published download tip and header tip without
  waiting for the long block-validation monitor on an unpruned node. Download
  peer-policy refresh also uses the published header tip.
- A subsequent 90-second profile revealed blocking IBD checks in outbound
  connection setup and premature chain-tip reads by Stratum. Outbound setup now
  reads the published IBD latch; mining readiness does likewise, and Stratum
  checks readiness before reading the active chain or building work.
  The latch is initialized from the persisted active tip, including on an
  already synchronized restart; a regression test covers this startup case.
- Application logs are persisted to `target/mainnet-node.log` by default;
  `BITCOIN_LOG_FILE`, `BITCOIN_ROCKSDB_BLOCK_CACHE_MIB` and
  `BITCOIN_IBD_UTXO_PREFETCH` can override these defaults.

## Verification

Storage tests include snapshot consistency across native writes, write-back
activation/flush, cache updates and concurrent close. Relay tests flood IBD with
transaction announcements while verifying headers remain serviceable. Peer tests
cover closed endpoint retention, unconditional removal and local overload without
discouragement. An RPC test holds the validation chain monitor while requesting
`getblockchaininfo`; it must complete before the monitor is released. Crash,
snapshot recovery, reorganization, download and service tests also ran.

The broad regression run executed 332 tests. Its only failure was an outdated
diagnostic expectation for a never-connected peer; the diagnostic was updated to
handle the newly nullable endpoint. A follow-up run passed all 28 RPC/coordinator
tests, including a new concurrent RPC test. The later connection/mining changes
passed 40 tests, with one additional opt-in latency benchmark skipped.
Startup-state and affected relay/lifecycle/mining tests passed in a final run.

Test logs: `target/ibd-relay-io-fixes-tests.log`,
`target/ibd-final-rpc-tests.log`, `target/ibd-followup-tests.log`.
Startup-state verification: `target/ibd-startup-state-tests.log`.

## First restart measurement

PID 13128 started at 15:22:09 (Asia/Bangkok). Over 298.732 seconds from 15:23:45
to 15:28:43, the active height increased from 481540 to 481645: 105 blocks,
21.09 blocks/minute. The earlier run measured 8.54 blocks/minute over 281 seconds;
the observed improvement is 2.47 times. These are adjacent historical block
ranges and different peer sets, not a controlled identical-block benchmark.

All 100 `getblockchaininfo` and 100 `getpeerinfo` calls succeeded. Chain RPC median
latency was 2.30 ms, maximum 109.21 ms; peer RPC maximum was 30.49 ms. The peer
pool reached ten live peers. Earlier observations had 26/58 chain RPC timeouts
and 58/58 peer RPC internal errors.

The 90-second profile still places 250 lifecycle samples in native RocksDB
MultiGet. Windows reports the K: HDD at approximately 100% active disk time;
the latest block metrics place almost all processing time in UTXO reads.
The download buffer holds roughly 1020 blocks while validation advances: network
download capacity exceeds processing capacity. CPU/script phases are small for
this historical range with the existing AssumeValid policy.

There were no relay queue saturation/protocol-overload closures. Two genuine
window-stall closures occurred early: the frontier block was unavailable from
its owners and was then fetched from another peer. Candidate connection timeouts,
remote EOF and transport errors also remain. Such transport errors alone cannot
distinguish a remote peer from a firewall or network path problem. The BIP324
helper's EOF message says "handshake" even when called while receiving a packet;
that wording is not proof that a READY peer failed its initial handshake.

## Follow-up restart measurement

PID 13128 was gracefully stopped through the Spring Boot Admin JMX endpoint.
The log records STOPPING -> STOPPED and the CUDA worker shutdown, followed by
PID 14260 starting at 15:30:16 with the connection/mining follow-up fixes.
RocksDB's LOG confirms the final process has cache capacity 2147483648 bytes.
Final measurements are recorded in `target/ibd-after-followup.jsonl` and its
summary; profiles and thread dumps use the same filename prefix.

From 15:30:29 to 15:33:27, height increased from 481675 to 481743: 68 blocks
in 178.099 seconds, 22.91 blocks/minute (2.68 times the original measurement).
All 60 chain RPC and 60 peer RPC calls succeeded, with ten live peers at the end.
Chain RPC median latency was 2.18 ms and maximum 97.93 ms.
The subsequent profile contains no monitor waits in Stratum or the outbound
connection IBD check; only five monitor events remain, mainly necessary header
serialization and short scheduler coordination. The lifecycle still spends most
samples in native UTXO reads (206 top-frame MultiGet samples).

During this process's observed interval, the log records two remote EOF closures,
two transport-read closures, zero window-stall closures and zero relay-overload
closures. Failed connection candidates continue to be retried independently of
these established-peer closure counts.

## Final running process

PID 14260 was gracefully stopped at 15:34:06 after the startup-state verification.
PID 28204 started at 15:34:20 with all changes, including IBD latch initialization.
Its final live check is saved as `target/ibd-final-live.jsonl`.
The final 87.735-second live check connected 43 blocks (481758 -> 481801),
29.41 blocks/minute, with all 30 chain/peer RPC sample pairs successful and nine
live peers. This shorter, different-block interval confirms continued progress;
the longer 3- and 5-minute measurements are preferable for the before/after
comparison. The process was left running.

No database files were moved. Random UTXO reads on the mechanical K: disk remain
the main physical limit; moving chainstate to SSD would require a separate
storage-layout change and data migration.
