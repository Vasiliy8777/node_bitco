# Mainnet after restart: throughput and peer churn

Observed PID 20436 on 2026-10-09. Effective JVM flags confirm
`TieredStopAtLevel=4`; RocksDB LOG confirms a 512 MiB native block cache.
The old linear common-ancestor traversal was absent from the observed stacks.

## Connected-block throughput

RPC sampling from 14:51:50 to 14:56:32 local time (Asia/Bangkok) measured
height 481374 to 481414: 40 connected blocks over 281.071 seconds,
0.1423 blocks/s or 8.54 blocks/minute. Sample timestamps precede each RPC
request, so bounded response latency adds a small uncertainty to the endpoints.
Of 58 blockchain requests, 32 succeeded and 26 exceeded the four-second
timeout. All 58 peer-info requests returned an error. This is a running-sync
sample, not total startup throughput. There is no equivalent timed baseline
from the previous process, so a numeric speedup ratio cannot be established.

Artifacts: `target/sync-after-restart-20261009.jsonl` and
`target/sync-after-restart-summary.json`.

## Remaining processing bottleneck

A 90-second JFR recording (14:53:09–14:54:39) contains 248 execution/native
samples attributed to the lifecycle thread. Of these, 220 are in UTXO
`RocksDB.multiGet` from `prefetchInitialSyncInputs`; another two are UTXO
overlay MultiGet. Sample proportions describe this recording, not exact wall
time percentages.

The chainstate is on K:, physical disk 3, WDC WD60EZAZ-00SF3B0, identified
by Windows as HDD. One performance-counter snapshot showed 100% disk time,
156 reads/s, roughly 0.9 MB/s and queue length 1. Raw-counter deltas in the
subsequent interval gave about 7.68 ms average read latency. Low byte throughput
does not imply an idle disk when reads are small and random.

`RocksDbDatabase.getAll` holds the database monitor across the native batch;
`processInitialSyncBatch` holds the chain monitor around prefetch/processing.
JFR monitor-enter events show:

- Header-tip lookup by `ibd-download-completions`: waits up to 11.20 s.
- Relay fee-filter calculation: waits up to 11.48 s.
- Relay mempool inspection: waits up to 10.19 s.
- RPC active-tip inspection: waits up to 9.49 s.

The profile includes the real node under instrumentation; it is not an isolated
disk or CPU benchmark. Startup RocksDB statistics showed no write stalls or
pending compaction; those startup statistics alone do not describe the entire run.

## Peer churn: local queue saturation plus connection failures

The recording contains nine `PeerProtocolException` events with the exact
message `Inbound relay work queue saturated`, originating at
`NodeRelayService.enqueue`. The worker is single-threaded and its task queue
is bounded to 128. Rejected task events report 128 queued tasks. The relay
thread is blocked behind chain validation, including transaction/mempool and
fee-filter work while IBD is still true. Queue rejection explicitly invokes
`disconnectForProtocolViolation`, making local overload look like peer fault.
These are nine exception events, not a proven count of nine distinct peers.

Separately, the same recording contains 13 TCP `Connect timed out` events,
four `Read timed out` events and BIP324 EOF events. TCP connection timeouts
indicate unreachable/nonresponsive candidates or path failures; they do not
identify whether an individual peer, firewall or network path is responsible.
Retries may include v2 and v1 attempts for the same candidate, so exception
counts are not counts of distinct remote endpoints. The observed local queue
saturation cannot be explained solely by bad remote peers.

## Closed peers remain in the registry

JFR records `IllegalStateException: Peer connection is not open` from
`PeerManager.onPeerClosed -> Peer.remoteAddress -> PeerConnection.remoteAddress`.
For a protocol-violation closure, onPeerClosed requests the remote address
before removing the peer. The socket has already been closed; the address
getter calls ensureConnected and throws. Removal is therefore skipped.

The same getter exception occurs in `NodeRpcServer` peer-info serialization.
The entire RPC fails instead of returning the remaining live peers. A later
`getconnectioncount` returned 34 while eight outgoing established TCP sockets
to port 8333 were visible; the count mismatch is supporting evidence, not a
one-to-one inventory of connection states.

## Follow-up changes suggested by the evidence

1. Stop transaction request/admission and unnecessary mempool/fee-filter work
   during IBD before it occupies the shared relay worker; keep block/header
   handling available. Treat local queue overload separately from protocol
   violations and coalesce periodic ticks.
2. Retain a safe remote-address snapshot for close diagnostics and make registry
   removal unconditional, even if discouragement/diagnostic handling fails.
   Make peer-info robust against concurrent closure.
3. Bound synchronous UTXO prefetch batches and avoid holding the global database
   monitor for long native reads. Reuse committed header metadata to avoid
   forcing the download scheduler through that monitor on every refresh.
4. Evaluate placing chainstate on the existing SSD; bulk block files can remain
   on HDD if the storage layout supports separate locations. No data was moved.

Recording: `target/sync-after-restart.jfr`; decoded events:
`target/sync-after-restart-jfr.json`; two current thread dumps:
`target/sync-after-restart-threads-{1,2}.txt`.

The application console is not saved to a file and the IDE exposes no reader
for an existing run console. Findings come from RocksDB LOG, live RPC, Windows
counters, thread dumps and JFR. No debugger session exists; the node was not
restarted for analysis. JFR ended automatically and the RPC sampler was stopped.
No production source or runtime configuration changed during this diagnosis.
