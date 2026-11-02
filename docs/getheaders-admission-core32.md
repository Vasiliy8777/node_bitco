# Shared GETHEADERS admission

Implemented 2026-11-02 against Core 32.1
[`MaybeSendGetHeaders` and `ProcessHeadersMessage`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp).

All GETHEADERS producers for one `Peer` now share a recent-request gate: initial/
live synchronous synchronization, asynchronous availability probes and recovery
checks. A new request is admitted only if no recent request remains outstanding,
or strictly more than 120,000,000 microseconds have elapsed. Different producers
cannot independently consume socket/write-queue capacity for the same recent
request. Non-header messages retain their existing transport behavior.

Empty HEADERS releases admission immediately. A nonempty decoded response does
not: the application marks it responded only after its header validator accepts
a connected batch. Compact-block headers do not masquerade as HEADERS responses.
Invalid/unconnected/malformed replies cannot reset the gate through the raw
dispatcher. Initial/live synchronization releases admission before advancing its
locator to request another batch, including known-tip confirmations.
Queued application processing of an empty response does not release admission
again: another producer may already have reserved the next request after the
dispatcher handled that response.

HeaderSynchronizer registers its existing dispatcher future before attempting a
send. If an earlier asynchronous request owns admission, that response may satisfy
the waiter. If the earlier response had already been dispatched to asynchronous
validation before registration, the waiter rechecks admission at bounded 100-ms
intervals and sends after validation releases it. These are future waits, not
additional socket reads. There is still one managed reader and one pending
synchronous headers future per peer. The original overall response timeout is
not extended while waiting for admission.

Transport submission failures release only their own generation's reservation;
a delayed failure cannot cancel a newer request. A caller's timeout/unregistration
does not pretend that the peer answered. Terminal future failures, including a
header-timeout exception, propagate rather than being mistaken for a short
coordination wait and retried in a busy loop.

Pure policy tests cover the strict two-minute boundary, response release,
generation-safe failure, clock rollback and concurrent producers. Socket tests
cover shared sync/async wire admission, empty versus nonempty release, consuming
an earlier asynchronous response, response dispatch before waiter registration,
overall timeout and terminal-failure propagation. Header coordinator tests also
verify that failed validation never signals a validated response. Existing peer
write budgets/deadlines and request ownership remain in force.

The full Bitcoin/RVN reactor passed 5,072 cases (5,060 passed, 12 opt-in skips)
and packaged both applications (`core32-getheaders-gate-full-build.log`). After
the final terminal-failure and queued-empty-response guards, 81 focused cases
passed, including real sockets, write deadlines and the native Core 32.1
77-scenario block acceptance comparison (`core32-getheaders-gate-final-guard.log`).

## Limits of correspondence

Core can clear its timestamp after proof-of-work/connection checks, before all
header acceptance processing finishes; this application releases after its
existing complete validator returns. Invalid peer replies are handled through
the existing failover/disconnection paths. Core's low-work presync state machine
is not reproduced by this admission change. GETHEADERS has no request ID, so a
connecting response may satisfy an outstanding waiter for an earlier locator.
This protocol constraint is handled by subsequent header validation/locator
advancement, not by inventing response IDs.

Complete Core equivalence, complete historical mainnet replay and missing
RPC/privacy-network/manual-connection functionality are not claimed by this work.
The original single-node IDE launch is retained; compilation happens only after
the running Bitcoin node has stopped.

Live verification at 18:16 Asia/Bangkok: exactly one original IDE process,
PID 79892, reached RUNNING after reusing two anchors. Authenticated RPC reported
height/headers 974249, IBD false, six connections including two block-relay peers.
CUDA resumed at approximately 2.26 GH/s with accepted shares. Evidence:
`target/core-update-audit/core32-getheaders-gate-live-verification.json`.
