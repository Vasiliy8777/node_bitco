# Unsupported Bloom service requests

Implemented 2026-11-03 against Bitcoin Core 32.1's
[`ProcessGetBlockData`, `FILTERLOAD`, `FILTERADD` and `FILTERCLEAR`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp).

The configured node does not offer NODE_BLOOM. Previously it silently ignored
filtered-block GETDATA and Bloom control messages. These requests now close the
connection without protocol discouragement when the service was not offered to
that peer, matching Core's disabled-service behavior.

The filtered-block check runs before any block lookup, independent of the hash
and body availability. Filtered blocks are classified as block work for bounded
GETDATA portions, so a filtered request at the front cannot trigger a scan of
later transaction requests before the service check. No BLOCK, MERKLEBLOCK or
NOTFOUND is sent for that unsupported request, and later batch entries are not
served after the close.

FILTERLOAD/FILTERADD/FILTERCLEAR now reach the bounded inbound relay worker.
Their offered-service check precedes payload decoding: empty or malformed
payloads still take the same ordinary-close path when NODE_BLOOM is absent.
Only exact MSG_FILTERED_BLOCK (3) receives this treatment; the reserved witness
filtered type and other unknown inventory types retain the unknown-type behavior.

The decision uses immutable local per-connection services. A remote Bloom flag
does not grant local service. NODE_COMPACT_FILTERS (BIP157/158) is independent of
NODE_BLOOM (BIP37/111). The new symbolic NODE_BLOOM constant is not added to default
or configured advertised services.

Tests cover filtered requests followed by ordinary block/transaction requests,
zero/nonzero hashes, no block lookup, each control command with empty/malformed
payloads, ordinary closes without protocol penalties, remote Bloom capability,
local compact-filter capability and unknown-command/type extensibility.

Full BIP37 filtering, Bloom payload parsing and MERKLEBLOCK serving are not
implemented. There is no supported configuration to offer NODE_BLOOM; this phase
adds disabled-service handling only. Historical upload limits, recent-block
caching, low-work header presync, permission features and complete mainnet replay
retain their documented limits. Complete Core equivalence is not claimed.

The original single `BitcoinNodeApplication GPU` IDE launch is retained.
Production is stopped before compilation and restarted after checks; no separate
deployment JAR or alternate launch configuration is introduced.

Focused verification passed 59 cases with zero failures/errors/skips:
`target/core-update-audit/core32-bloom-guard-focused.log`. The run includes the
three new Bloom-guard cases, legacy inventory/serving tests, write deadlines,
node configuration and the native Core 32.1 acceptance matrix (77 scenarios
inside one JUnit case).

Full Bitcoin/RVN verification passed 5,104 total cases: 5,092 passed, 12 opt-in
skips, zero failures/errors. Both applications packaged successfully. Log:
`target/core-update-audit/core32-bloom-guard-full-build.log`.

Live verification at 11:41 Asia/Bangkok on November 3: original IDE configuration,
PID 6076, RUNNING, exactly one Bitcoin node. The node caught up blocks discovered
during startup. Authenticated RPC reported height/headers 974345, IBD false, ten
connections including two block-relay peers. Two saved anchors were consumed;
CUDA resumed at approximately 2.54 GH/s with accepted shares. Evidence:
`target/core-update-audit/core32-bloom-guard-live-verification.json`.
