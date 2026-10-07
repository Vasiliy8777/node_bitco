# Amortized connection-window index preparation

## Measured problem and Core reference

The preceding testnet observation processed 472 blocks in one five-second interval
but spent 1224.4 ms on 147 skip-index reads. BlockSyncCoordinator extended its
materialized index path by exactly the newly needed count after each ordered
connection batch, normally 32 blocks. Each extension independently resolved its
end from the best-header tip and performed another ancestor traversal.

[Bitcoin Core v31.1 GetAncestor](https://github.com/bitcoin/bitcoin/blob/v31.1/src/chain.cpp)
follows parent/skip pointers in memory. Our skip selection already follows that
algorithm, but the Java persistent representation makes cold traversal expensive.
This change amortizes preparation; it does not claim an identical index layout.

## Change

The first materialized window remains exactly the logical download horizon plus
its stall probe, allowing the initial requests to start without extra preparation.
Subsequent extensions prepare at least 128 index records at a time, clipped to
the existing bounded materialization chunk and remaining path. This retains at
most 127 extra indexes relative to the required preparation frontier. Existing
bounded MultiGet warming applies to each extension.

The network request horizon remains 1024 blocks. Ordered validation still yields
after at most 32 blocks and its existing input workload bound. Index preparation
still finds the selected branch's ancestor, follows exact parent hashes, checks
heights and verifies that each extension connects to the preceding index. No
body is downloaded merely because its index was prepared. No consensus check,
peer request limit, timeout or chain selection rule changes.

The existing shared lookup supplied through NodeConfiguration/NodeSyncInfrastructure
is reused. No additional cache configuration or database migration is needed.

## Verification

`target/ibd-core-policy/index-extension-tests.log`: BUILD SUCCESS. The new 4096-block
regression asserts that requests never exceed the moving 1024-block horizon,
all 4096 hashes are requested once, connection batches remain at most 32 blocks,
lookahead stays bounded and ancestor traversals are at most 26. Existing tests
cover initial-window submission before extra preparation, cancellation, sliding
window refill, local chunk boundaries and Core stall policy.

Initial full verification, before the additional HEADERS correction below:
`target/ibd-core-policy/index-extension-full-tests.log`, BUILD SUCCESS, 3938 tests,
zero failures/errors, six skipped in 360 classes, 2m30s.

## Availability HEADERS correction discovered in the live run

The first live run had nine ready peers but only one downloading. Telemetry
attributed thousands of persistent index misses to CoreBlockDownloadPeerPolicy.refresh.
A non-suspending debugger logpoint at CoreBlockDownloadPeerPolicy.java:62 confirmed
repeated lookups of `00000000009574be05c392b1d53299d73bcc04c7ce27dc43a409604f43a3b847`,
with `found=false`, `best=-1`, `header=5156978`. These events occurred at
2026-10-07T06:47:54.962Z. Logpoint evaluation reported no errors; the agent's
logpoints were removed and the debug process stopped before rebuilding.

PeerMessageDispatcher records the last announced hash from HEADERS. If no pending
headers future owns the response, it forwards the message to peer listeners.
CoreBlockDownloadPeerPolicy uses sendAsync for its availability GETHEADERS, so
these responses reach NodeRelayService. That service previously filtered out
HEADERS entirely: even a valid tip extension remained unknown, preventing a peer
from becoming eligible and repeatedly querying the same unknown hash.

NodeRelayService now accepts HEADERS on its existing bounded worker/byte budget,
checks batch continuity and invokes the existing HeaderSyncService validation and
persistence. Invalid header consensus validation is treated as a protocol violation.
No best-known peer index is trusted before local validation/persistence. Unknown
parent recovery and complete Core low-work header presync remain separate work.

NodeValidationService.processHeaders serializes relay header persistence with
block activation/failed-branch updates using the existing chain monitor. Compact
and locally submitted relay block headers use this boundary as well. HeaderBatchProcessor
holds HeaderChainState's monitor across durable and in-memory tip publication,
so concurrent peer refresh cannot publish that same tip between write and consider.

`target/ibd-core-policy/index-extension-headers-regressions.log`: BUILD SUCCESS.
The real-database regression starts with an unknown announcement and an ineligible
peer, delivers HEADERS through the relay listener and confirms durable header
availability and download eligibility without connecting any body. It then confirms
invalid PoW rejection, no invalid index persistence and peer disconnection.
An initial test compile failed on the test NetworkAddress constructor; another
attempt exposed concurrent Mockito stubbing while the worker was active. Test
setup now finishes before starting the worker. These failures were test setup
issues, corrected before the successful run.

The diagnostic console is
`target/ibd-core-policy/testnet-index-extension-before-headers.log`. Its throughput
is not used as a speed comparison because peer eligibility was broken and debugger
logpoints were installed during the run.

Core speed parity still requires comparable block ranges, peer/disk conditions
and validation settings; consecutive public-network runs are observational.

## First combined run and preparation quantum adjustment

Before reducing the extension quantum, the full combined HEADERS/256-extension
verification passed: `target/ibd-core-policy/index-extension-headers-full-tests.log`,
3939 tests in 360 classes, zero failures/errors, six skipped, BUILD SUCCESS in 2m27s.

`target/ibd-core-policy/testnet-index-extension-headers-console.log`: the first
combined live run recovered from a 120-second initial-header timeout against
44.241.182.5 (advertised height 2401568, local headers 5156978), switched to a new
peer and began block download. Multiple peers participated after header processing
was repaired; a representative sample had seven downloading peers. No HEADERS
processing errors appeared. The timeout delay is separate from block-rate samples.

After the first positive block rate and before shutdown: 215 one-second observations,
heights 851498 to 872688, mean displayed speed 98.77 blocks/s, median 91, maximum
256 and 25 zero-rate observations. The database was not reset. PID 132256 stopped
cleanly and was verified absent. Agent logpoints were absent throughout this run.

The large preparation quantum still produced bursty progress and more zero-rate
seconds than the previous height-hint observation. It was reduced from 256 to 128
to bound each synchronous preparation pause more tightly. This is a measured
tradeoff, not evidence that all zeros were caused by batching: block contents and
network conditions also differed. Final verification and the smaller-quantum
observation follow below.

## Final 128-extension verification and observation

`target/ibd-core-policy/index-extension-128-headers-full-tests.log`: BUILD SUCCESS
in 2m40s, 3939 tests in 360 classes, zero failures/errors and six skipped. Includes
actual Core roundtrip/reorg, relay, coordinator, configuration and crash recovery.

`target/ibd-core-policy/testnet-index-extension-128-headers-console.log`: existing
K:/BitcoinJavaNode/testnet3 database, no reset, no agent logpoints. After the first
positive displayed rate and before shutdown: 215 observations, heights 872732 to
892561, mean 91.82 blocks/s, median 128, maximum 256, 39 zero-rate observations.
The final 120 observations had mean 100.67 blocks/s and 17 zeros. These complete
results supersede any impressions from individual fast intervals. Sustained
throughput/stability improvement over the previous run is NOT established.

The HEADERS fix restored actual participation of multiple peers (up to eight
downloading in observed samples), without HEADERS processing errors. It addresses
the demonstrated availability-state defect regardless of noisy throughput results.
The smaller preparation quantum reduces the bounded amount of synchronous index
preparation, but different-block runs do not prove it caused lower latency.

At 14:09:56 local time, an interval processed only two blocks in 6081 ms despite
1022 buffered bodies and an available frontier. UTXO reads accounted for 4279 keys
and 6043.2 ms; measured processor work was only 27 ms. Ahead warming had already
read 13059 keys cumulatively. This is storage/input-prefetch cost, not evidence of
a stalled network frontier. Larger blocks are not split or rejected to avoid a
zero console sample; consensus validation remains ordered and complete.

At height 888904, another interval processed 768 blocks at 140.5 blocks/s,
with primary index reads 767/3065.7 ms and skip reads 247/1402.9 ms. Cold index
ancestry remains important alongside cold UTXO input reads. These measurements
do not show that all skip I/O was eliminated by amortizing coordinator extensions:
peer branch-membership checks still independently resolve ancestors.

PID 91516 stopped cleanly at 14:12:45 local time and was verified absent. Agent
logpoints were removed; the user's disabled exception breakpoints were preserved.
Next work should target cold UTXO reads and shared, hash-verified ancestry reuse,
with bounded memory and correct handling of forks, failure changes and restarts.
Complete Core synchronization architecture/speed parity is not established.
