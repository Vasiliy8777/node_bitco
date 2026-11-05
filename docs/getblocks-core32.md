# Legacy GETBLOCKS inventory and continuation

Implemented 2026-11-03 against Bitcoin Core 32.1's
[`GETBLOCKS` and `ProcessGetBlockData`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp).

GETBLOCKS now reaches the relay's bounded inbound worker. Its message/codec uses
the existing version/locator/hashStop wire format, with canonical CompactSize,
strict framing and at most 101 locator hashes. Empty locators use genesis fork
selection; unlike null-locator GETHEADERS, they do not request hashStop itself.
An oversized locator disconnects without protocol discouragement, matching Core;
the same typed limit error is handled for GETHEADERS. Malformed framing retains
the existing protocol-violation path.

`blockInventory` selects active-chain successors after Core-style ordered locator
fork lookup. It excludes hashStop and returns at most 500 MSG_BLOCK hashes. It
does not apply GETHEADERS' minimum-chainwork guard. Empty results produce no INV.
On pruned nodes, enumeration stops at unavailable data or a height at/below
active height minus (288 minus one hour divided by target spacing), reproducing
the retention margin used by Core's GETBLOCKS handler. Selection runs under the
chain lock with bounded ancestry/cache reads, without loading block bodies.

A full 500-hash result stores one continuation hash per peer. A subsequent short
page does not erase it. After that peer successfully requests the marked block,
the block response is followed immediately by an INV for the current active tip,
even if that tip was announced already. Atomic compare-and-clear consumes the
marker once. Legacy/witness/compact GETDATA share the hook; old GETBLOCKTXN's full
witness-block fallback also uses it. Failed/missing body requests do not consume
the marker. Peer removal discards its marker with the other announcement state.

Tests build real 502/512-block chains and verify the full-page boundary, exclusive
stop, null-locator behavior, no empty INV, per-peer isolation, preservation across
a short page, duplicate tip INV, one-shot consumption and old GETBLOCKTXN fallback.
A real manual-prune fixture verifies the exact age-height cutoff and missing-body
stop. Wire tests cover empty/101-hash locators, truncation, trailing bytes,
noncanonical counts and oversized-locator classification.

## Remaining limits

Core explicitly calls ActivateBestChain on GETBLOCKS; this application queries
its synchronously committed active chain instead of adding a network-triggered
full block-index scan/activation operation. Core's inventory/send deque and
continuation tip snapshot timing differ from the per-peer worker implementation.
Historical upload targets, Bloom support,
low-work headers presync and complete mainnet replay remain outside this phase.
Complete Core equivalence is not claimed.
The later [limited-history serving phase](limited-history-serving-core32.md)
adds the advertised NODE_NETWORK_LIMITED block-serving window.

The original single `BitcoinNodeApplication GPU` IDE launch is retained. Production
is stopped before compilation and restarted after checks; no deployment JAR or
alternate launch configuration is introduced.

Focused verification passed all 61 cases with zero failures/errors/skips:
`target/core-update-audit/core32-getblocks-focused.log`. The run includes the new
wire/legacy inventory cases, existing serving/locator/header tests, peer write
deadlines and the native Core 32.1 block-acceptance matrix (77 scenarios inside
one JUnit case).

Full Bitcoin/RVN verification passed 5,097 total cases: 5,085 passed, 12 opt-in
skips, zero failures/errors. Both applications packaged successfully. Log:
`target/core-update-audit/core32-getblocks-full-build.log`.

Live verification at 03:15 Asia/Bangkok on November 3: original IDE configuration,
PID 87448, RUNNING, exactly one Bitcoin node. The node caught up a block learned
during restart. Authenticated RPC reported height/headers 974293, IBD false, seven
connections including two block-relay peers. Two saved anchors were consumed,
and CUDA resumed with accepted shares. Evidence:
`target/core-update-audit/core32-getblocks-live-verification.json`.
