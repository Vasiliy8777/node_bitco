# New-body reception credit and recovery scheduling

Implemented 2026-11-02 against Core 32.1
[`PeerManagerImpl::ProcessBlock`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp)
and the connection loop in
[`net.cpp`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net.cpp).

Core's block-source usefulness timestamp is updated when a new block body is
accepted for storage, including a side-chain body awaiting activation. It is not
restricted to blocks that become the active tip.

`NodeValidationService.processBlockWithReception` now returns the existing
processing result together with `newBody`. Data availability is read before and
after ordinary validation/storage under the same chain monitor. A known header
without a body still produces a new-body receipt. A duplicate body does not,
including when two callers submit it concurrently. Unknown-parent submissions,
invalid bodies and storage failures cannot manufacture a successful receipt.
The availability lookup does not read and parse the whole stored body.

The reconstructed compact-block and full-fallback paths credit the source from
this receipt. Ordered bulk downloads use `processInitialSyncBatchWithReception`,
capturing availability under the same lock and crediting each newly stored hash
once per batch. The coordinator preserves each completion's source peer.
Active-tip postprocessing remains conditional on `CONNECTED` and
does not double-credit the source. Side-chain reception does not advance the
active-tip progress clock, clean up confirmed-transaction orphans or announce the
body as an active-chain block. Bulk post-connect processing uses a hook that does
not credit the source again. The original public post-connect hook remains
available to other callers. Reception metadata does not
change a context-pending branch into a fully contextually validated one.

Six tests use real regtest competing branches to cover header-only/new-body
distinction, duplicate bodies, unknown parents, malformed bodies, concurrent
delivery, batch replay, compact reconstruction and full fallback. A queued GETHEADERS response
acts as a barrier when asserting that replayed compact data earns no extra credit.

Recovery scheduling is split into two cadences. A 500-ms pump handles connection
probes, initial headers and chainwork catch-up checks. Extra-peer eviction remains
on its 45-second timer; stale-tip decisions remain gated by that timer and their
existing ten-minute interval. Eviction scheduling uses monotonic time so wall-clock
changes cannot collapse its grace interval. An integration test checks that an
initial headers request happens within three seconds, while existing extra-peer
tests verify that a fast tick alone does not perform scheduled eviction.

The full Bitcoin/RVN reactor passed 5039 cases with 12 opt-in cases skipped
(5051 total; `core32-block-reception-full-build.log`). After adding bulk reception
accounting, the final package run completed 99 cases: 98 passed and one opt-in
mining case skipped (`core32-block-reception-batch-final.log`). It includes all
six reception tests, 27 block-sync coordinator cases, recovery/rotation/network
tests and the native Core block-acceptance matrix (77 scenarios). Both applications
packaged successfully; no background-thread exceptions appeared in the final run.

## Remaining limits

- The later timer-precision phase removes whole-second probe rounding; chainwork
  checks still run in Core's message-processing loop versus this recovery pump.
- The subsequent [peer-event phase](peer-events-core32.md) assigns protection
  on validated header events and records age/order before handshakes at TCP
  establishment. The timer-precision phase subsequently carries grace timestamps
  in microseconds.
- Manual-connection accounting, privacy-network transport support, complete RPC
  coverage and complete historical mainnet replay remain outside the demonstrated
  correspondence.

The original `BitcoinNodeApplication GPU` launch is retained. The single mainnet
node is stopped before rebuilding its classpath and restarted after verification;
no separate deployment JAR is created.

For this deployment PID 68192 stopped cleanly and saved two anchors before the
first compilation. After final checks, PID 77172 launched through the original
IDE configuration and reached `RUNNING` at 13:36:34 Asia/Bangkok. Authenticated
RPC reported height/headers 974222, IBD false and ten peers including two
block-relay peers. CUDA reported about 2.61 GH/s and 25 accepted shares. Process
enumeration confirmed exactly one Bitcoin node and no packaged run configuration.
Live evidence is `target/core-update-audit/core32-block-reception-live-verification.json`.
