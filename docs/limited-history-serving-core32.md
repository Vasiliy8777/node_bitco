# NODE_NETWORK_LIMITED block-serving window

Implemented 2026-11-03 against Bitcoin Core 32.1's
[`ProcessGetBlockData`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp).

`Peer.localServices()` exposes the immutable services offered to that connection.
Serving decisions now use those flags, rather than the remote peer's services or
the current pruning configuration. This matters because advertised services are
fixed for a session. A NODE_NETWORK_LIMITED-only session closes without protocol
discouragement when a known eligible requested block is more than 290 heights
behind the active tip (288 plus Core's two-block race buffer). Exactly 290 remains
allowed. NODE_NETWORK overrides the limitation even when both bits are set.

The limit is checked under the validation chain lock after block eligibility and
before body availability. Thus an old known active block causes the same close
whether its body is retained or pruned; the request cannot probe the precise
pruning boundary through this distinction. Unknown hashes and ineligible branches
remain silently ignored, matching the earlier checks in Core's handler.

Legacy, witness and compact GETDATA requests share this gate. An old GETBLOCKTXN
request whose body is still available takes the ordinary witness-block fallback
and applies it too. GETBLOCKTXN checks body availability before that fallback;
therefore an already-pruned old GETBLOCKTXN is ignored, as in Core. Recent
GETBLOCKTXN responses retain their separate ten-height rule.

The existing single-argument validation lookup APIs remain available for local
callers; network serving passes each peer's actual offered services explicitly.
Expected window denial uses an ordinary connection close, without a protocol
penalty or a local-worker error log. The body/compact response still uses the
existing bounded outbound worker and continuation hook.

Tests build real 292-block chains and exercise the 290/291 boundary, all three
GETDATA block forms, both service bits, unknown hashes, retained versus missing
old bodies, GETBLOCKTXN fallback and recent transaction responses. Pure policy
checks also cover absent/full/limited flags and candidate heights above the tip.

## Remaining limits

The subsequent [peer-access batch](peer-access-core32.md) implements and exercises
the explicitly approved NoBan history-window exception while retaining data
availability and block eligibility checks. Recent-block caching and low-work
headers presync were also added in later phases. Historical upload targets,
Bloom-filtered blocks and complete mainnet replay remain outside this phase.
Complete Core equivalence is not claimed.

The original single `BitcoinNodeApplication GPU` IDE configuration is retained.
Production is stopped before compilation and restarted after checks; no separate
deployment JAR or alternate launch configuration is introduced.

Focused verification passed all 65 cases with zero failures/errors/skips:
`target/core-update-audit/core32-limited-serving-focused.log`. This includes the
new window/availability cases, existing legacy inventory and block serving,
write deadlines and the native Core 32.1 acceptance matrix (77 scenarios inside
one JUnit case).

Full Bitcoin/RVN verification passed 5,101 total cases: 5,089 passed, 12 opt-in
skips, zero failures/errors. Both applications packaged successfully. Log:
`target/core-update-audit/core32-limited-serving-full-build.log`.

Live verification at 10:30 Asia/Bangkok on November 3: original IDE configuration,
PID 93400, RUNNING, exactly one Bitcoin node. Authenticated RPC reported height/
headers 974337, IBD false, nine connections including one block-relay peer. Two
saved anchors were consumed during startup. CUDA resumed at approximately
2.56 GH/s with 24 accepted shares. Evidence:
`target/core-update-audit/core32-limited-serving-live-verification.json`.
