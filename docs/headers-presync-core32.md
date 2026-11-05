# Low-work header PRESYNC/REDOWNLOAD — Bitcoin Core 32.1

References: [headerssync.cpp](https://github.com/bitcoin/bitcoin/blob/v32.1/src/headerssync.cpp),
[network parameters](https://github.com/bitcoin/bitcoin/blob/v32.1/src/kernel/chainparams.cpp),
[network processing](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp),
[permitted difficulty transitions](https://github.com/bitcoin/bitcoin/blob/v32.1/src/pow.cpp).

The previously unused SHA-256 segment helper is replaced with Core's two-phase
state machine and connected to production reception. The per-peer controller is
shared by initial synchronization, live synchronization through the same
coordinator, and asynchronous relay. A pending synchronous waiter can continue a
session started by asynchronous delivery without losing its cursor/commitments.

The work threshold is the larger of configured network minimum work and active
tip work minus 144 current-difficulty blocks, clamped at zero. Stored ancestors
of the best header or active tip bypass redundant low-work synchronization.
Other short low-work replies are ignored without storing indexes. Full replies
start PRESYNC and request subsequent batches using their last hash plus the
stored start-chain locator.

Whole-message PoW and continuity checks precede the controller. PRESYNC stores
salted SipHash commitment bits at a randomized offset; it does not retain full
headers or publish a durable header receipt. The maximum number of commitments
uses Core's six-blocks-per-second bound from start MTP through local time plus
two hours. Difficulty changes must satisfy Core's permitted transition bounds.

After sufficient claimed work, REDOWNLOAD restarts at the stored chain start.
It verifies commitments and difficulty transitions and holds bounded lookahead
before releasing headers to the existing contextual validator and atomic index
writer. Compressed headers contain 48 serialized bytes without their parent;
Java array/deque overhead differs from Core's C++ heap layout. Contextual
validation still checks actual required difficulty, MTP/BIP94, version and PoW.
No block bodies become active merely because their headers were released.

Core 32.1 parameters are used for all networks:

| Network | Commitment period | Redownload lookahead |
| --- | ---: | ---: |
| Mainnet | 649 | 15400 |
| Testnet3 | 682 | 14613 |
| Testnet4 | 616 | 16199 |
| Signet | 629 | 15885 |
| Regtest | 275 | 7017 |

Empty replies, failed sessions and peer closure release the peer's state.
Sessions belong to connection objects, so independent peers and reconnections
cannot overwrite another peer's commitments. All follow-up requests use the
existing shared GETHEADERS gate. An unconnected reply does not acknowledge that
gate. Compact-block reception checks the same work threshold before persisting
headers, preventing that path from bypassing the low-work guard.

When REDOWNLOAD finishes on a full message, ordinary GETHEADERS continuation
resumes from the accepted last header. This detail was corrected after the
native Core wire test showed that suppressing the final request was wrong. An
empty response terminates pagination.

Verification includes seven production state-machine tests and seven real P2P
integration tests. These cover 20,000 streamed headers and lookahead bounds,
changed commitments, short replies, clock/commitment limits, transition bounds,
all network parameters, a 6000-header initial sync, asynchronous-to-synchronous
handoff, two independent peers, cancellation/reset and compact-block bypass.

`BitcoinCorePresyncWireTest` independently starts the actual Core 32.1 binary in
isolated regtest data/ports with minimum work 9000, sends the same 6000-header
chain over P2P twice, and checks request locators and RPC index visibility. Both
implementations retain height zero after the first pass, accept 6000 headers
after REDOWNLOAD, keep active block height zero, and request the final ordinary
continuation. The final targeted package run passed all 48 cases with zero
failures/errors/skips: `target/core-update-audit/core32-presync-native-final.log`.

The subsequent complete Bitcoin/RVN package run passed 5,148 cases: 5,136
executed, 12 opt-in skips, zero failures/errors. It includes the native PRESYNC
wire comparison and existing block-acceptance matrix on the final production
code. Log: `target/core-update-audit/core32-presync-integration-final-full.log`.

Live verification on November 4 at approximately 21:15 Asia/Bangkok: exactly
one Bitcoin process, PID 106736, original IDEA `BitcoinNodeApplication GPU`
configuration using `app/target/classes`. State RUNNING; authenticated RPC
height/headers 974534, IBD false, ten connections including two block-relay
peers. Two saved anchors loaded. CUDA resumed at approximately 2.57 GH/s with
accepted shares. Evidence:
`target/core-update-audit/core32-presync-integration-live-verification.json`.

The original automatic review rejection was resolved by the user's explicit
integration approval. The isolated model remains as a historical review fixture;
the production implementation and actual P2P/native tests are authoritative.

Scope: low-work header state-machine behavior for ordinary peers. The subsequent
[peer-access batch](peer-access-core32.md) adds the explicitly approved NoBan
exception; MANUAL alone does not bypass PRESYNC. The subsequent
[bootstrap/PRESYNC RPC batch](manual-bootstrap-presync-rpc-core32.md) adds the
actual per-peer presynced_headers field. Broader RPC statistics remain tracked
separately. Java memory allocation is not byte-identical to Core,
and this is not a full historical mainnet replay or complete Core equivalence.
