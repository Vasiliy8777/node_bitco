# Peer creation order and event-driven chain-sync protection

Implemented 2026-11-02 against Core 32.1
[`UpdatePeerStateForReceivedHeaders`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp)
and outbound extra-peer eviction in the same file.

`PeerConnection` now records a process-wide increasing connection identity and
TCP establishment time before transport/version handshake processing. Accepted
sockets receive metadata when adopted by their `PeerConnection`. Closing a socket
retains its last identity/time for observers; reconnecting creates a fresh identity.
The supervisor uses this identity and timestamp rather than assigning them when
the READY peer reaches a pool slot. A slow handshake can therefore neither make
an older connection appear newest nor restart its thirty-second eviction grace
period. Promotion between slots preserves the original connection metadata.

`PeerManager` owns the four-entry chain-sync protection set. A complete validated
headers response with at least active-tip work can admit a READY full-relay peer.
Admission order is the order of those events, independent of slot/pool order.
Repeated responses do not consume extra quota. Inbound, block-relay, feeler,
unmanaged and not-ready peers cannot enter the set. Closing/removing a peer frees
its quota; manager shutdown clears all protection state.

Admission is called from asynchronous HEADERS/compact-header processing, initial
header synchronization and live synchronous header synchronization. The header
coordinator's observer runs after a complete response validates and before the
already-known-tip early return. Invalid responses do not notify the observer.
Empty HEADERS messages do not grant protection. The production recovery view now
reads this event-driven set: resolving an inventory hash against an existing
header can demonstrate availability, but cannot itself claim protection.

The supervisor consumes the protection set when making eviction decisions.
Protection remains session-long until the peer leaves, as Core's chain-sync
protection does; it is not revoked solely because the active tip advances or the
peer's current available-work estimate changes.

Tests cover first-four admission in reverse pool order, idempotence, role/readiness
exclusions, quota release, shutdown cleanup, TCP metadata before handshake,
reconnection identity, known-tip notification and invalid-response suppression.
A real-socket integration test establishes two TCP connections, completes their
handshakes in reverse order and verifies that eviction retains the connection
chosen by TCP creation order. Relay tests distinguish known inventory availability
from validated-header protection.

The full Bitcoin/RVN reactor completed 5058 cases: 5046 passed, 12 opt-in cases
skipped, zero failures/errors (`core32-peer-event-full-build.log`). After the
final best-known-work check, all 77 focused cases passed and both applications
packaged (`core32-peer-event-final-confirmation.log`). This final run includes
transport/write-deadline tests, event quota/cleanup, reverse handshake order,
header/live/lifecycle integration and the native Core block-acceptance matrix
(77 scenarios inside one JUnit case). No background-thread exceptions appeared.

## Remaining limits

- The subsequent [timer-precision phase](peer-timing-precision-core32.md) carries
  TCP age and probe deadlines in microseconds, including strict/inclusive grace
  boundaries and nearest-microsecond exponential interval rounding.
- Core's chainwork checks run in its message-processing loop; this application
  uses its 500-ms recovery pump.
- Manual connections, privacy-network transports, full RPC coverage and complete
  historical mainnet replay remain outside demonstrated correspondence.

The single Bitcoin node is stopped before compilation and restarted from the
original `BitcoinNodeApplication GPU` IDE configuration after checks. No alternate
deployment JAR or run configuration is introduced.

PID 77172 stopped cleanly and saved two anchors before compilation. After all
checks, PID 77728 started from the original IDE configuration and reached
`RUNNING` at 16:03:31 Asia/Bangkok. Authenticated RPC reported height/headers
974233, IBD false and six connections including two block-relay peers. CUDA
reported about 2.54 GH/s and 16 accepted shares. Process enumeration confirmed
exactly one Bitcoin node. Evidence is
`target/core-update-audit/core32-peer-event-live-verification.json`.
