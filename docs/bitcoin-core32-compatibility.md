# Bitcoin Core 32.1 compatibility — 2026-11-01

Reference: [Bitcoin Core v32.1](https://github.com/bitcoin/bitcoin/releases/tag/v32.1),
released October 28, 2026. This is a Java implementation with its own identity,
storage engine and supported RPC surface. This document records behavior actually
implemented and checked, rather than asserting complete Core compatibility.

For the rules affecting block construction, verification and network acceptance,
see [the block consensus coverage report](block-consensus-core32.md). The native
matrix compares actual Core/Java block acceptance, including valid nonstandard
transactions and exact weight/sigops boundaries.
The [operational review](node-efficiency-core32.md) covers bounded network work
and reuse of unchanged mempool cluster computations.

## Implemented behavior

| Area | Implementation and checks |
| --- | --- |
| Stale-tip full-relay recovery | One temporary extra connection, 45-second eviction checks, ten-minute stale checks, three-spacing progress threshold, download/chainwork/network protections and supervised promotion. See [scope and remaining differences](stale-tip-recovery-core32.md). |
| Block-relay rotation and chainwork catch-up | Exponential five-minute block probes share the single extra-connection allowance; youngest-pair eviction retains useful peers. Unprotected connections have twenty minutes to reach a work benchmark, then one headers request and two minutes before disconnection. See [checks and cadence limitations](outbound-rotation-core32.md). |
| Missing-network probe | With eight full-relay peers, an independent exponential five-minute timer can connect to a missing known IPv4/IPv6 network. Strict preferred-network selection shares the extra-slot budget; sole-network protection retains the new network during eviction. See [scope and tests](extra-network-probe-core32.md). |
| Block-source credit and scheduling | Atomic new-body receipts credit stored side-chain bodies and reject duplicate credit. A 500-ms probe/catch-up pump is separated from 45-second extra-peer eviction. See [verification and limits](block-reception-and-scheduling-core32.md). |
| Connection age and chain-sync protection | TCP identity/time precedes handshakes, so READY order cannot reverse eviction age. Four-peer protection is assigned on validated initial/live/asynchronous header events, released on peer removal and excludes other roles. See [tests and remaining precision limits](peer-events-core32.md). |
| Timer precision | TCP age and block/network probe deadlines retain microseconds. Full-relay grace is strict `>30s`, block-relay is inclusive `>=30s`; exponential five-minute draws use nearest-microsecond rounding and `SecureRandom`. See [boundaries and scheduling limits](peer-timing-precision-core32.md). |
| GETHEADERS coordination | Sync/live/availability/recovery requests share one recent-request gate per peer. Strict two-minute admission, validated response release and bounded waiter coordination prevent duplicate recent requests without another socket reader or an extended overall timeout. See [tests and validation-stage limits](getheaders-admission-core32.md). |
| Global transaction relay, #34628 | Shared inbound/outbound backlogs, independent of peer count. Count refill 14/35 transactions per second, initial/capped count 420, initial size 12 MB, cap 50 MB, size refill 20/50 kB/s. Core's negative count/size floors permit bounded bursts. Queue processing runs at 100 ms intervals. Per-peer randomized trickle remains 5/2 seconds. |
| Relay ordering | Mining-score chunk merge preserves each cluster's order, including equal-feerate parent/child chunks. At the actual trickle slot, transactions are re-resolved, evicted/replaced entries removed, and current ordering and fee filters applied. |
| Cluster linearization | Exact fractional maximum-closure optimization includes shared ancestors across multiple descendants. Residual components split optimal sets into minimal chunks, ordered by weight and a deterministic hash fallback. Arithmetic uses exact integer cross-products, including negative modified fees. 384 independent Core C++ cases compare full ordering and minimal chunk membership/fees/weights. `getmempoolinfo.optimal` reflects the exact solver. |
| Cluster work reuse | Per-mempool cache retains ordering metadata for at most 65,536 transactions. Membership, modified fee and sigops-adjusted size determine reuse; merged, split, removed and changed clusters are recomputed. No transaction bodies or UTXOs are retained by the cache. Template selection consumes one immutable entry/cluster snapshot, and independent transactions bypass the graph solver. |
| Mempool diagram RPC | `getmempoolfeeratediagram` includes the origin `(weight=0, fee=0)` for both empty and populated pools, followed by cumulative chunk endpoints. |
| Relay diagnostics | `getnetworkinfo.inv_buckets.inbound/outbound` expose `backlog`, `count_tok`, `size_tok`. |
| Inbound budget, #28463/#35919 | Default automatic total 200, default outbound targets still 8 full-relay and 2 block-relay peers, yielding 190 inbound slots. Full-relay inbound cap defaults to 50%. Configured totals above 2000 are clamped with a warning. Zero automatic budget starts without DNS/outbound synchronization and does not arm mining. |
| Inbound eviction | Core's netgroup, ping, useful transaction/block, block-relay, network-ratio and connection-age protections precede preferred eviction and largest-group selection. Both total capacity and full-relay capacity can replace an eligible existing peer. Discouraged peers are accepted only with spare capacity. Successful validation updates peer usefulness; rejected transactions and unresolved orphans do not. Default IPv4/IPv6 netgroup rules and per-process SipHash keys are used. |
| Block-relay anchors | Up to two READY block-relay peers are persisted on clean shutdown and tried preferentially for block-relay slots on restart. The file is consumed once; invalid, banned, unsuitable or duplicate-network candidates are skipped and connection failures fall back to AddrMan. See `block-relay-anchors-core32.md` for format and bootstrap limitations. |
| Parallel prevouts, #35295 | Configurable 0–16 workers, default 8. Persistent misses are split into ordered MultiGet partitions; only the caller publishes results to the coin cache and block overlay. Reads include buffered creates/deletes. Existing speculative IBD warming remains a separate option. |
| Block-policy fee estimator | Core's fee buckets, three decay horizons, confirmation/failure accounting, sample thresholds and smart-fee target selection. Package/child/reorg-restored/IBD transactions do not become eligible standalone samples. |
| Mempool fee estimator, #34075 | Six-block coverage window, 75% coverage threshold when activity spans at least one block, 50th/75th block-weight percentiles, relay/mempool minimum floor, seven-second tip-bound cache. |
| Combined fee RPC | `estimatesmartfee conf_target estimate_mode options` implements `auto`, `block_policy`, `mempool_policy` and verbosity ≥2 health statistics. AUTO requires a valid block-policy estimate, then chooses the lower valid estimate, as Core does. Missing data returns errors rather than fabricated fees. |
| Fee persistence | `fees/mempool_policy_estimator.dat` (Core v1) and `fees/block_policy_estimates.dat` (Core v309900); writes use temporary files and replacement. Statistical data is staged and validated before loading. Both codecs read actual Core-written files in the independent test. No unconfirmed-tracking state is persisted as though it were confirmed. |
| OpenRPC, #34683 | `getopenrpcinfo` and `rpc.discover` return the same OpenRPC 1.4.1 document for implemented methods. Help, discovery and named-argument mapping share one registry. Methods not implemented locally are not advertised. Schemas describe supported top-level types; they do not reproduce every nested Core result constraint. |
| RPC arguments | Named arguments are converted to the implemented positional interface with optional defaults and unknown-name rejection. JSON-RPC batches, notifications and authentication remain supported. |
| Transaction RPC, #32800 | Verbose mempool `getrawtransaction` adds `vsize_adjusted`. Existing mempool entry/probe/package endpoints retain explicit adjusted and BIP141 sizes. |
| Mining defaults | Default maximum block weight 4,000,000; reserved weight 8000; reserved coinbase sigop cost 400; minimum mining fee 1 sat/kvB. Core's strict chunk-budget boundaries are applied. RPC and Stratum use the same defaults. |
| RPC transport | Default 16 open HTTP clients, including idle connections, enforced with the JDK HTTP server connection limit. The limit is process-wide and must be chosen before the first HTTP server starts. |
| Earlier maintenance fixes | RFC 9637 filtering, retired testnet3 seed removal, DNS v2 capability and omission of obsolete `fullrbf` remain in place. |

Sources:
[cluster linearization](https://github.com/bitcoin/bitcoin/blob/v32.1/src/cluster_linearize.h),
[eviction](https://github.com/bitcoin/bitcoin/blob/v32.1/src/node/eviction.cpp),
[network groups](https://github.com/bitcoin/bitcoin/blob/v32.1/src/netgroup.cpp),
[relay](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp),
[mempool estimator](https://github.com/bitcoin/bitcoin/blob/v32.1/src/policy/fees/mempool_estimator.cpp),
[block-policy estimator](https://github.com/bitcoin/bitcoin/blob/v32.1/src/policy/fees/block_policy_estimator.cpp),
[estimator selection](https://github.com/bitcoin/bitcoin/blob/v32.1/src/policy/fees/estimator_man.cpp),
[fee RPC](https://github.com/bitcoin/bitcoin/blob/v32.1/src/rpc/fees.cpp),
[mining](https://github.com/bitcoin/bitcoin/blob/v32.1/src/node/miner.cpp),
[policy defaults](https://github.com/bitcoin/bitcoin/blob/v32.1/src/policy/policy.h).
Adapted algorithms carry Core attribution; the MIT license is packaged in the
mempool and p2p modules under `META-INF/licenses/bitcoin-core-MIT.txt`.

## Configuration

Settings use this project's Spring configuration, not Core command-line syntax:

```yaml
bitcoin:
  prevoutfetchthreads: 8
  p2p:
    maxconnections: 200
    inboundrelaypercent: 50
    txsendrate: 14
    target-outbound-peers: 8
    target-block-relay-peers: 2
  rpc:
    maxconnections: 16
  mining:
    maximum-weight: 4000000
    minimum-fee-sat-per-kvb: 1
    block-reserved-weight: 8000
    coinbase-output-max-additional-sigops: 400
```

An existing explicit `bitcoin.p2p.max-inbound-peers` remains an additional cap
inside the automatic budget. The prevout worker pool is configured before block
validation and drained before the database closes.

The HTTP limit uses the JDK's documented
[`jdk.httpserver.maxConnections`](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.httpserver/module-summary.html)
property. This is distinct from the RPC worker count, queue size and long-poll limit.

## Independent validation

The reference binary was downloaded into ignored `target/core-update-audit/core32/`
from [bitcoincore.org](https://bitcoincore.org/bin/bitcoin-core-32.1/).
Its archive SHA-256 matches the official HTTPS checksum manifest:
`b5b0c2cd007d69e96880907c79f9ae19ae82728f95fc8c923b4a07dab795b45d`.
No system Core installation or mainnet Core data directory was changed.

`BitcoinCore32CompatibilityTest` starts its own Core regtest process and Java
database. It checks discovery and exact JSON fee results on a cold start, imports
200 Core blocks, then admits 64 independent wallet-signed transactions over eight
more blocks. Each round compares all three estimator choices in both fee modes,
including health statistics. It also checks Core-written estimator files after
shutdown. No expected fee is generated from the Java implementation.

The final native comparison also broadcasts a wallet-signed two-output parent
and two paying children to both nodes. Core combines the three into one chunk;
the complete `getmempoolcluster` result and whole-mempool diagram match exactly.
Empty diagrams are compared too. These tests use HTTP for structured parameters
to avoid Windows command-line JSON quoting changes.

`BitcoinCoreMiningRoundTripTest` checks testnet4 genesis and Core's acceptance of
a Java-built block with relayed transactions, followed by reconnect. CUDA and the
separate bulk IBD benchmark remain opt-in; a skipped benchmark is not a pass.

Unit and integration tests additionally cover burst limits, directional refill,
byte limits, stale relay entries, equal-feerate dependencies, inbound slot release,
buffered UTXO mutations, estimator history gaps/reorgs, disk corruption and cold
RPC error cases. Full reactor and reference-run logs are in
`target/core-update-audit/`.

The latest full Bitcoin/RVN reactor completed 5,072 test cases: 5,060 executed
successfully, 12 opt-in cases skipped, zero failures/errors. Package completed
successfully for both applications (`core32-getheaders-gate-full-build.log`).
The final GETHEADERS race guards then passed 81 focused cases, including native
Core block acceptance (`core32-getheaders-gate-final-guard.log`). See
`getheaders-admission-core32.md` for the shared request gate and its limits. The
earlier final bulk-reception run additionally covered 27 block-sync coordinator
cases and the 77-scenario native block acceptance matrix
(`core32-block-reception-batch-final.log`). Peer-event verification is documented
in `peer-events-core32.md`; timer precision is covered in
`peer-timing-precision-core32.md`. The 192
eviction reference cases run unchanged Core C++ selection/protection code to
produce expected answers; normal tests read its stored fixture. See
`p2p/reference/README.md` for provenance and regeneration. Real-socket tests cover
replacement at total/full-relay capacity, protected-pool rejection, discouraged
admission and shutdown during incomplete handshakes. A subsequent 17-case relay
run passed after completing mock version negotiation; its log contains no
background-thread exceptions (`core32-relay-fixture-tests.log`).
After the final diagram RPC correction, 398 focused cases passed and the Bitcoin
application was packaged again (`core32-cluster-final-focused.log`). This run
includes all cluster vectors, the native Core comparison and mining RPC tests.

## Live deployment verification

At the user's request, deployment uses the original `BitcoinNodeApplication GPU`
IDE configuration and `app/target/classes`. The packaged process was shut down
cleanly before restoring the original launch. The added packaged
configuration and deployment JAR copies were removed. `mainnet,gpu`, the existing
data root and `data/ssd-chainstate` override are retained. Stop this single
development node before rebuilding its classpath, then restart after the build.
The next network-probe update followed that order: PID 75736 stopped before
compilation, and PID 68192 started only after the full and focused checks passed.
The reception/scheduling update likewise stopped PID 68192 before compilation
and started the single replacement, PID 77172, after final checks.
The peer-event update then stopped PID 77172 before compilation and started only
PID 77728 after the full and final 77-case runs passed.
The timer-precision update stopped PID 77728 before compilation and started only
PID 55596 after the full 5063-case reactor passed (12 opt-in skips).
The GETHEADERS update stopped PID 55596 before compilation, then started only
PID 79892 using the same original configuration after the full and final checks.

At approximately 18:16 Asia/Bangkok on November 2, authenticated live RPC reported
mainnet height/headers 974249, `initialblockdownload=false`, six peer connections
including two block-relay peers. Startup consumed the two saved anchors and
reached `RUNNING`. CUDA logged approximately 2.26 GH/s and five accepted shares.
Process enumeration confirmed exactly one Bitcoin node.
Earlier discovery checks verified OpenRPC 1.4.1 and
52 documented methods; the method set did not change in this phase.

Fee estimators report insufficient recent data until they have observed the
required blocks. Live checks are saved in
`target/core-update-audit/core32-getheaders-gate-live-verification.json`; the timer
check is `core32-timing-precision-live-verification.json`; the peer-event
check is `core32-peer-event-live-verification.json`. The reception
check is `core32-block-reception-live-verification.json`. The previous
network-probe check is `core32-network-probe-live-verification.json`. The restored
launch's earlier check is `core32-original-launch-live.json`. Earlier rotation
checks remain in `core32-rotation-live-verification.json` and
`core32-rotation-live-threads.txt`. Earlier recovery checks remain in
`core32-recovery-live-verification.json`. Earlier anchor checks remain in
`core32-anchors-live-verification.json` and `core32-anchors-reuse-live.json`.
Earlier efficiency checks remain in
`core32-efficiency-live-verification.json`. Earlier
eviction and named-argument verification remain in
`core32-eviction-live-verification.json` and `core32-live-verification.json`.

## Limits of the compatibility claim

- Restart anchors, stale-tip full-relay probing, periodic block-relay rotation and
  chainwork catch-up eviction and missing IPv4/IPv6 network probes are implemented.
  Privacy-network transports/manual accounting remain absent; scheduler architecture
  and JVM/OS clock resolution still differ as documented in
  `peer-timing-precision-core32.md`.
  Initial header
  bootstrap still precedes anchor attempts; see `block-relay-anchors-core32.md`.
- Eviction selection matches 192 independent Core C++ reference cases. When all
  compared fields tie, Java's stable sort can choose a different equivalent peer
  than C++'s unspecified sort ordering. The live server currently supplies IPv4/
  IPv6 peers without NoBan permissions or Bloom filters; the selection algorithm
  supports those flags and privacy-network protections for future transports.
- Cluster linearization uses an exact maximum-closure solver rather than Core's
  budgeted spanning-forest optimizer. The 384 reference cases require Core to
  finish optimization and minimization. They do not compare its budget-limited
  intermediate outputs. Equal-rate fallback uses transaction hashes locally,
  which may differ from Core's internal transaction index order. Regeneration
  and the standalone reference RNG shim are documented in
  `mempool/reference/README.md`.
- Prevout work uses parallel bounded MultiGet partitions and a Java overlay; it
  does not reproduce Core's internal coins-cache scheduling or memory allocation.
- OpenRPC nested schemas and the overall RPC method set remain a subset of Core.
  GUI, wallet, Tor/I2P session management, private broadcast, external ASMap files
  and Core's native IPC/build system are not supplied by this application.
- Background AssumeUTXO RPC reporting is not yet a complete reproduction of Core
  32.1's `backgroundvalidation` object. Existing historical validation/readiness
  requirements are unchanged.
- These tests do not establish complete historical mainnet replay, every possible
  fee-estimator workload, or byte-for-byte compatibility of RocksDB with Core's
  LevelDB storage.

These differences remain explicit; the node does not advertise itself as Bitcoin
Core or report an invented Core version to imply certification.
