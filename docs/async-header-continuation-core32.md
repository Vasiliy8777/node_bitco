# Full asynchronous HEADERS response continuation

Implemented 2026-11-03 against Bitcoin Core 32.1's
[`ProcessHeadersMessage`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp).

The synchronous header coordinator already advanced its locator across download
batches. Asynchronous availability/recovery HEADERS replies were validated and
stored but did not request another batch when the peer returned the wire maximum
of 2000 headers. That could postpone discovery of the rest of the peer's chain
until another synchronization round.

After a complete asynchronous response validates, the relay releases the shared
recent-GETHEADERS admission gate. If the response contains exactly 2000 headers
and its last index is not reported failed by the download view, it queues another
GETHEADERS with a locator starting at that last index, zero hashStop and the
negotiated protocol version. The ordinary bounded per-peer sender uses the same
Peer request gate as synchronous, availability and recovery producers. A competing
request can suppress the queued request; no second socket reader is introduced.

Full already-known responses may request more, as in Core; new-header credit is
not the pagination criterion. Short or empty asynchronous responses do not start
a continuation loop. Invalid batches publish no receipt/release/continuation,
and a last index reported failed cannot drive further requests. Compact-block
header processing retains its distinct path and does not trigger full-page logic.
Synchronous replies continue to route to the existing pending dispatcher future
and are advanced by their coordinator.

Tests use real persisted regtest header batches: 2000-header continuation from the
last hash, negotiated version/hashStop, release-before-send order, a known full
batch, short/empty replies, an invalid final header with no persisted prefix and
a download view reporting the branch failed. Existing socket tests exercise shared
request admission across generic, asynchronous and synchronous producers.

## Remaining limits

This change does not implement Core's low-work headers PRESYNC/REDOWNLOAD state
machine or its minimum-work anti-DoS storage policy. Existing complete-batch
validation and persistence remain the release boundary, which is later than
Core's PoW/connection-stage timestamp reset. Queue scheduling differs from Core's
message-processing loop. Complete Core equivalence and full historical mainnet
replay are not claimed.

The original single `BitcoinNodeApplication GPU` IDE launch is retained. Production
is stopped before compilation and restarted after checks; no deployment JAR or
alternate launch configuration is introduced.

Focused verification passed all 55 cases with zero failures/errors/skips:
`target/core-update-audit/core32-async-header-continuation-focused.log`. This
includes the three new full-batch cases, synchronous coordinator/streaming/failover
checks, real-socket request admission, peer write deadlines and the native Core
32.1 acceptance matrix (77 scenarios inside one JUnit case).

Full Bitcoin/RVN verification passed 5,107 total cases: 5,095 passed, 12 opt-in
skips, zero failures/errors. Both applications packaged successfully. Log:
`target/core-update-audit/core32-async-header-continuation-full-build.log`.

Live verification at 13:59 Asia/Bangkok on November 3: original IDE configuration,
PID 75104, RUNNING, exactly one Bitcoin node. Authenticated RPC reported height/
headers 974362, IBD false, nine connections including two block-relay peers. Two
saved anchors were consumed. CUDA resumed at approximately 2.48 GH/s with accepted
shares. Evidence:
`target/core-update-audit/core32-async-header-continuation-live-verification.json`.
