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
| Local peer access and manual connections | Explicit immutable CIDR rules, misbehavior/local-address handling, manual reconnect, RPC metadata and explicitly approved NoBan PRESYNC/history and Download GETHEADERS exceptions. See [implementation, tests and remaining scope](peer-access-core32.md). |
| Live NoBan header timeout | An unanswered GETHEADERS switches source without disconnecting NoBan or fabricating a response acknowledgment. Transport failures retain cleanup. Four regression variants and related sync checks passed; [scope/evidence](live-header-timeout-core32.md). |
| Live header rejection | Future time and inbound cached-invalid do not directly close a peer. Other header rejections delegate to the protocol-misbehavior handler, preserving NoBan/manual rules. [Regression/runtime evidence](live-header-rejection-core32.md). |
| Initial header rejection/failover | Header validation and malformed-response errors are peer-local and select another known source instead of failing startup; internal state errors remain fatal. [Regression/runtime evidence](initial-header-rejection-core32.md). |
| Initial NoBan header timeout | An open NoBan peer remains registered/connected while another source is tried after a header timeout; transport failures retain cleanup. [Regression/runtime evidence](initial-noban-timeout-core32.md). |
| Manual-only bootstrap / PRESYNC RPC | Explicit manual peers can bootstrap headers/blocks with zero automatic budget, pass normal mining-readiness checks and cancel while waiting. RPC reports real presync height or -1. [Real P2P/RPC evidence](manual-bootstrap-presync-rpc-core32.md). |
| Conflict-free mempool admission | Indexed affected-component traversal, capacity-checked append and maintained virtual-byte total avoid whole-pool reconstruction during ordinary/restored admission. Count overflow terminates traversal early. Full budgeted Core optimizer remains open. [Checks and limits](mempool-admission-local-work-core32.md). |
| RBF preflight / cluster weight / rejection name | Prospective count/size checks precede RBF diagram optimization. Cluster size uses aggregate adjusted weight. Single testmempoolaccept exposes too-large-cluster. Signed native Core RBF and graph rounding cases pass; [scope/evidence](rbf-cluster-preflight-core32.md). |
| Low-work header PRESYNC/REDOWNLOAD | Per-connection production state is shared by initial/live/async reception; salted commitments, dynamic work threshold, bounded lookahead, reset and final ordinary continuation are checked against real P2P traffic and native Core 32.1. See [implementation and limits](headers-presync-core32.md). |
| Seven requested workstreams, first batch | Pre-contextual GETHEADERS admission release; negotiated compact-block decode; recent-block GETBLOCKTXN cache; persistent transaction-based AssumeUTXO reporting and correct snapshot base hash. See [implementation and verification](core32-seven-first-batch.md) and [open workstream checklist](core32-seven-workstreams.md). |
| Unconnected header announcements | HEADERS with valid claimed PoW but missing ancestry trigger best-header-locator recovery without success credit. Unknown availability can resolve after another peer supplies the chain; compact blocks request recovery only outside IBD. See [checks and scope](unconnected-header-announcements-core32.md). |
| Future header rejection | HEADERS and compact announcements beyond the two-hour bound are rejected without punishing the peer and can be retried when time advances. Invalid work cannot obtain that exemption. See [checks and scope](future-header-rejection-core32.md). |
| Explicitly invalid headers | Explicitly failed duplicates and new children of an explicitly failed parent are rejected without a successful batch receipt. Known descendants retain Core 32.1 semantics; inbound cached-invalid duplicates are not punished. See [checks and scope](cached-invalid-headers-core32.md). |
| Full asynchronous HEADERS continuation | A validated 2000-header availability/recovery response requests the next locator from its last nonfailed index. Short/empty responses do not loop; the shared request gate still governs every producer. See [checks and presync limits](async-header-continuation-core32.md). |
| Disabled Bloom service | Without offered NODE_BLOOM, filtered-block GETDATA and FILTERLOAD/FILTERADD/FILTERCLEAR close without discouragement, before block lookup/payload parsing. Compact-filter service remains independent. See [checks and BIP37 scope](bloom-disabled-core32.md). |
| Limited-history serving | Per-connection offered services enforce Core's strict >290-height disconnect before body availability; NODE_NETWORK overrides NODE_NETWORK_LIMITED. Legacy/witness/compact GETDATA and old GETBLOCKTXN fallback share the rule. See [tests and permission limits](limited-history-serving-core32.md). |
| GETBLOCKS | Bounded 500-hash INV, exclusive hashStop, prune retention/availability checks and per-peer one-shot continuation after serving the marked block. Empty inventories are silent; oversized locators close without discouragement. See [checks and activation/scheduling limits](getblocks-core32.md). |
| Locator fork selection | Ordered first-match lookup, known-descendant shortcut to active tip and genesis fallback follow Core's FindForkInGlobalIndex. Header-only descendants avoid redundant header-prefix replies and preserve later tip announcements. See [checks and scope](locator-fork-core32.md). |
| GETBLOCKTXN | Recent stored side-chain bodies use Core's inclusive ten-height rule. Older requests use the ordinary witness-block eligibility gate and full-block fallback. Empty index lists and invalid recent indexes disconnect. See [checks and cache/scheduling limits](blocktxn-serving-core32.md). |
| GETDATA block serving | Recent script-validated former tips share the direct-header eligibility gate. Missing/ineligible block requests are ignored; compact replies require fresh active-tip time and inclusive five-block height depth, otherwise a full witness block is served. See [checks and remaining serving limits](side-block-serving-core32.md). |
| Direct header requests | A null GETHEADERS locator requests exactly hashStop. Unknown/ineligible hashes are ignored; validated recent former tips are eligible under strict timestamp/proof-equivalent 30-day limits. See [wire form, eligibility and tests](direct-header-requests-core32.md). |
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

The latest full Bitcoin/RVN reactor completed 5,225 test cases: 5,213 passed,
12 opt-in cases skipped, zero failures/errors. Both applications packaged
successfully (`core32-ordinary-base-fee-full-build.log`, November 6 01:15:48
Bangkok). It includes ordinary INV base-fee filtering, Mempool permission/BIP35, Relay/ForceRelay, blocksonly, unsolicited admission,
the ADDR permission/privacy cache, final
RBF/cluster-weight/RPC corrections and the native signed replacement scenario.
Original IDEA GPU node PID 130828 was verified RUNNING at height/headers
974688/974688 with IBD=false, eight connections (two block relay), 37,866
mempool entries and accepted GPU shares; exactly one Bitcoin process and no
privileged peers were present. [Ordinary INV base-fee fix](ordinary-relay-base-fee-core32.md),
[Mempool implementation and remaining scope](mempool-permission-core32.md),
[Relay implementation and remaining scope](relay-permissions-core32.md),
[ADDR implementation](addr-permission-core32.md).
Earlier batch history: the cluster-count traversal-cap refinement passed all 506
mempool cases and reactor dependencies, followed by packaging with tests skipped
(`core32-mempool-admission-final-tests.log`, `core32-mempool-admission-final-package.log`).
Those older logs retain their original source scope; the latest full run above
covers all current production changes. Older
batch histories below retain their original counts and logs.
The earlier live header-timeout change passed all 43 selected synchronization,
PRESYNC and manual-connection cases with zero skips/failures/errors
(`core32-header-timeout-focused.log`); packaging then succeeded with tests skipped
(`core32-header-timeout-package.log`). These production changes are now also
covered by the latest full run above.
The earlier live header-rejection fix passed 63 selected cases with zero
skips/failures/errors (`core32-live-header-rejection-focused.log`), including
initial lifecycle, live timeout/rejection, PRESYNC, database admission and peer
access/discouragement tests. It is also included in the latest full run.
Packaging for this patch succeeded with tests skipped after the successful
63-case run (`core32-live-header-rejection-package.log`).
The subsequent initial header-rejection change passed 50 selected cases with no
skips/failures/errors (`core32-initial-header-rejection-focused.log`), then packaged
successfully with tests skipped (`core32-initial-header-rejection-package.log`).
This change is also included in the latest full run.
The initial NoBan header-timeout follow-up passed 53 selected cases without
skips/failures/errors (`core32-initial-noban-timeout-focused.log`). This targeted
verification is separate from the older complete-reactor baseline.
Its subsequent package run succeeded with tests skipped
(`core32-initial-noban-timeout-package.log`).
The final peer-access package rebuild then passed all 75 selected cases after
the late-registration shutdown guard and inbound-capacity test were added
(`core32-peer-access-final.log`). The subsequent permission-exemptions batch
implements the user's explicitly approved resource exceptions.
This final run includes all PRESYNC state/network tests and the native Core
PRESYNC wire comparison after correcting final full-message continuation.
This includes pre-contextual GETHEADERS admission, compact-version negotiation,
recent-block GETBLOCKTXN cache and transaction-based background reporting.
This includes unconnected HEADERS recovery, whole-announcement PoW sanity,
later resolution of unknown availability and compact missing-parent IBD handling.
This includes the final inbound/outbound and compact-block cached-invalid
distinctions, future-time rejection/retry and invalid-work reason precedence.
The focused future-header run passed all 49 cases (`core32-future-headers-focused.log`).
This full run includes full asynchronous HEADERS continuation, disabled Bloom service handling, advertised limited-history
serving, GETBLOCKS inventory/continuation/pruning, locator
order/descendant handling, GETBLOCKTXN height/fallback checks, eligible side-block
serving, direct-header requests, the HEADERS continuity guard and
the native Core block acceptance matrix. The earlier header-reception final run
is `core32-header-reception-final.log`.
See `header-reception-core32.md` for useful-announcement receipts and continuity,
and `getheaders-admission-core32.md` for the shared request gate and its limits. The
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
The header-reception update was compiled while production was stopped; PID 82052
then started from that original configuration after the 69-case final run passed.
The direct-header update stopped PID 82052 cleanly before compilation and started
only PID 80676 after the full 5081-case repeat passed (12 opt-in skips).
The side-block-serving update stopped PID 80676 before compilation and started
only PID 73408 after the full 5085-case build passed (12 opt-in skips).
The GETBLOCKTXN update stopped PID 73408 before compilation and started only
PID 78176 after the full 5089-case build passed (12 opt-in skips).
The locator-fork update stopped PID 78176 before compilation and started only
PID 79744 after the full 5092-case build passed (12 opt-in skips).
The GETBLOCKS update stopped PID 79744 before compilation and started only
PID 87448 after the full 5097-case build passed (12 opt-in skips).
The limited-history-serving update stopped PID 87448 before compilation and
started only PID 93400 after the full 5101-case build passed (12 opt-in skips).
The disabled-Bloom-service update stopped PID 93400 before compilation and
started only PID 6076 after the full 5104-case build passed (12 opt-in skips).
The asynchronous-header-continuation update stopped PID 6076 before compilation
and started only PID 75104 after the full 5107-case build passed (12 opt-in skips).
The cached-invalid-header update stopped PID 75104 before compilation, passed the
full 5109-case reactor (12 opt-in skips), then passed all 56 focused cases after
the inbound/outbound distinction was added. It started only PID 84308 through
the original IDEA GPU configuration, using `app/target/classes`.
The future-header update stopped PID 84308 before compilation and started only
PID 92880 through the same original configuration after the full 5119-case
reactor passed (12 opt-in skips).
The unconnected-header update stopped PID 92880 before compilation and started
only PID 95780 through the original IDEA GPU configuration after the full
5126-case reactor passed (12 opt-in skips).
The first seven-workstream batch stopped PID 95780 before compilation and started
only PID 94220 through the same original configuration after the full 5133-case
reactor passed (12 opt-in skips).
The resumed PRESYNC integration stopped the then-running original IDEA process
PID 95104 before final compilation. After all 5148 full-reactor cases completed
(12 opt-in skips), it started only PID 106736 through the original GPU
configuration. The native Core wire comparison is included in this final run.
The peer-access/manual-connection batch stopped PID 106736 before compilation,
passed the full 5163-case reactor (12 opt-in skips), then passed all 75 selected
cases on the final package including the manual shutdown race guard. It started
only PID 105152 through the original IDEA GPU configuration. Resource-related
NoBan exemptions were implemented in the subsequent explicitly approved batch.

After the resource-exemptions full build stopped PID 105152, the original IDEA
GPU configuration started exactly one Bitcoin process, PID 118444.
At approximately 01:03 Asia/Bangkok on November 5, authenticated live RPC reported
mainnet height/headers 974559, `initialblockdownload=false`, seven peer connections
including two block-relay peers. The node reached `RUNNING`.
CUDA resumed and logged accepted shares.
Process enumeration confirmed exactly one Bitcoin node. RPC showed zero peers
with locally assigned permissions. CUDA accepted shares at approximately 2.51 GH/s.
Evidence: `target/core-update-audit/core32-permission-exemptions-live-verification.json`.

The subsequent live NoBan header-timeout fix stopped PID 118444 before compiling
and restarted the same IDEA GPU configuration as PID 115896. At approximately
02:00 Bangkok on November 5, RPC confirmed height/headers 974567, IBD false,
eleven connections (three block-relay during startup/rotation), and zero
privileged peers. The node was RUNNING; exactly one Bitcoin process existed.
CUDA logged accepted shares at approximately 2.58 GH/s. Evidence:
`target/core-update-audit/core32-header-timeout-live-verification.json`.

The subsequent live header-rejection patch stopped PID 115896 before compilation
and restarted the same IDEA GPU configuration as PID 111352. At approximately
13:17 Bangkok on November 5, authenticated RPC confirmed height/headers 974631,
IBD false, nine connections including two block-relay peers, and zero privileged
peers. The node was RUNNING, exactly one Bitcoin process existed, and CUDA
accepted shares. Two anchors loaded; startup initialization took 215 seconds.
Evidence: `target/core-update-audit/core32-live-header-rejection-live-verification.json`.

The subsequent initial header-rejection patch stopped PID 111352 before compiling
and started the same IDEA GPU configuration as PID 113440. At approximately
13:53 Bangkok on November 5, RPC confirmed height/headers 974633, IBD false,
nine connections including two block-relay peers, and zero privileged peers.
The node was RUNNING; exactly one Bitcoin process existed. Two anchors loaded;
CUDA accepted shares at approximately 2.43 GH/s. Initialization took 169 seconds.
Evidence: `target/core-update-audit/core32-initial-header-rejection-live-verification.json`.

The subsequent initial NoBan timeout patch stopped PID 113440 before compiling
and restarted the original IDEA GPU configuration as PID 120452. At approximately
14:29 Bangkok on November 5, RPC confirmed height/headers 974638, IBD false,
eight connections including two block-relay peers, and zero privileged peers.
The node was RUNNING; process enumeration confirmed exactly one Bitcoin node.
Two anchors loaded and CUDA accepted shares at approximately 2.54 GH/s.
Evidence: `target/core-update-audit/core32-initial-noban-timeout-live-verification.json`.

After the manual bootstrap/PRESYNC RPC batch, original IDEA GPU configuration
PID 126060 was verified RUNNING at approximately 18:19 Bangkok on November 5.
RPC height/headers were 974660, IBD false, ten connections including two
block-relay peers, zero privileged peers and presynced_headers=-1 for all ten.
Process enumeration confirmed exactly one Bitcoin node. Two anchors loaded;
CUDA resumed accepted shares (approximately 2.17 GH/s at the recorded sample).
Evidence: `target/core-update-audit/core32-manual-presync-live-verification.json`.

After the conflict-free mempool admission batch, original IDEA GPU process
PID 127816 was verified RUNNING at approximately 18:50 Bangkok on November 5.
RPC confirmed height/headers 974664, IBD false, seven connections including two
block-relay peers and 24,180 mempool transactions. Exactly one Bitcoin process
existed; two anchors loaded and CUDA accepted shares (approximately 2.21 GH/s).
Spring initialization was 8.534 seconds versus 324.841 on the previous run;
these are operational observations, not a controlled same-workload benchmark.
Evidence: `target/core-update-audit/core32-mempool-admission-live-verification.json`.

After the RBF preflight/aggregate-weight/RPC batch, original IDEA GPU process
PID 129184 was verified RUNNING at approximately 19:34 Bangkok on November 5.
RPC reported height/headers 974668, IBD false, eight connections including two
block-relay peers and 23,428 mempool transactions. Exactly one Bitcoin process
existed. Two anchors loaded and CUDA accepted shares at approximately 2.32 GH/s.
Spring initialization took 7.041 seconds. Evidence:
`target/core-update-audit/core32-rbf-weight-live-verification.json`.
Earlier discovery checks verified OpenRPC 1.4.1 and
52 documented methods; the method set did not change in this phase.

Fee estimators report insufficient recent data until they have observed the
required blocks. Live checks are saved in
`target/core-update-audit/core32-peer-access-live-verification.json`; the
previous presync check is `core32-presync-integration-live-verification.json`; the
previous first-batch check is `core32-seven-first-live-verification.json`; the
previous unconnected-header check is `core32-unconnected-headers-live-verification.json`; the
previous future-header check is `core32-future-headers-live-verification.json`; the
previous cached-invalid check is `core32-cached-invalid-live-verification.json`; the
previous continuation check is `core32-async-header-continuation-live-verification.json`; the
disabled-Bloom check is `core32-bloom-guard-live-verification.json`; the
limited-history check is `core32-limited-serving-live-verification.json`; the
GETBLOCKS check is `core32-getblocks-live-verification.json`; the
locator-fork check is `core32-locator-fork-live-verification.json`; the
GETBLOCKTXN check is `core32-blocktxn-serving-live-verification.json`; the
side-block-serving check is `core32-side-block-serving-live-verification.json`; the
direct-header check is `core32-direct-header-live-verification.json`; the
header-reception check is `core32-header-reception-live-verification.json`; the
GETHEADERS check is `core32-getheaders-gate-live-verification.json`; the timer
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

Useful new-header announcement credit is now shared by synchronous initial/live
downloads and asynchronous HEADERS/compact-header handling. Reception metadata is
produced during serialized batch processing rather than a racing external lookup;
see [header reception](header-reception-core32.md) for scope and verification.

## Limits of the compatibility claim

- Restart anchors, stale-tip full-relay probing, periodic block-relay rotation and
  chainwork catch-up eviction and missing IPv4/IPv6 network probes are implemented.
  Manual-role accounting/reconnection and manual-only bootstrap are implemented;
  complete permission/failover behavior remains open. Privacy-network
  transports remain absent; scheduler architecture
  and JVM/OS clock resolution still differ as documented in
  `peer-timing-precision-core32.md`.
  Initial header
  bootstrap still precedes anchor attempts; see `block-relay-anchors-core32.md`.
- Eviction selection matches 192 independent Core C++ reference cases. When all
  compared fields tie, Java's stable sort can choose a different equivalent peer
  than C++'s unspecified sort ordering. The live server currently supplies IPv4/
  IPv6 peers, including explicitly configured NoBan permissions; Bloom filters
  and privacy-network transports remain unsupported by the live serving path.
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
- Background AssumeUTXO reporting was implemented and tested in the later
  background-report batch; the broader RPC method/schema inventory remains
  incomplete. Existing historical validation/readiness requirements are unchanged.
- These tests do not establish complete historical mainnet replay, every possible
  fee-estimator workload, or byte-for-byte compatibility of RocksDB with Core's
  LevelDB storage.

These differences remain explicit; the node does not advertise itself as Bitcoin
Core or report an invented Core version to imply certification.
