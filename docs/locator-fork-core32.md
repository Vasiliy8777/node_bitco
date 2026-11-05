# Locator order and known descendants

Implemented 2026-11-03 against Bitcoin Core 32.1's
[`Chainstate::FindForkInGlobalIndex`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp).

The node previously searched all locator entries for the highest active-chain
height and ignored known indexes above the active height. It now follows Core's
ordered search: skip unknown hashes, return the first active-chain match, or
return the active tip when an entry is a known descendant of that tip. If no
entry qualifies, return genesis. Locator order remains significant even if a
peer sends its entries out of the usual descending-height order.

The descendant check uses persisted ancestry/skip pointers. A valid header-only
continuation can therefore show that the peer already knows our active prefix
without requiring its block body to have been downloaded or activating that
header. A competing branch above active height does not trigger this shortcut.

GETHEADERS uses the resulting fork to choose successors. In particular, a locator
starting with a known descendant now receives an empty response instead of a
redundant prefix starting after genesis. Ordinary empty replies establish the
active tip as the peer's best sent header through the existing relay state. A
subsequently received/activated body can still be announced with HEADERS. Null
locators retain their separate direct hashStop-request behavior.

Tests cover first-match order, unknown/empty fallback, a header-only continuation,
a taller competing header branch, unchanged active-tip selection and a real relay
worker round trip followed by the next block announcement. The existing INV
fallback test now uses a transaction request as its queue barrier: GETHEADERS
itself establishes sent-header knowledge and cannot represent an unknown-header
state after its reply.

The subsequent [GETBLOCKS phase](getblocks-core32.md) adds that legacy handler
using this fork selection. Neither phase reproduces Core's in-memory chain/index
implementation. Other documented transport, RPC,
low-work presync and historical-replay limits remain. Complete Core equivalence
is not claimed.

The original single `BitcoinNodeApplication GPU` IDE launch is retained. Production
is stopped before compilation and restarted after checks; no deployment JAR or
alternate run configuration is introduced.

Focused verification passed 55 cases with zero failures/errors/skips:
`target/core-update-audit/core32-locator-fork-focused.log`. This includes the three
new locator/relay cases, existing header/block-transaction handling, write deadlines
and the native Core 32.1 block acceptance matrix (77 scenarios inside one JUnit case).

Full Bitcoin/RVN verification passed 5,092 total cases: 5,080 passed, 12 opt-in
skips, zero failures/errors. Both applications packaged successfully. Log:
`target/core-update-audit/core32-locator-fork-full-build.log`.

Live verification at 02:53 Asia/Bangkok on November 3: the original IDE launch,
PID 79744, reached RUNNING; exactly one Bitcoin node was present. Authenticated
RPC reported height/headers 974291, IBD false, five connections including two
block-relay peers. Two saved anchors were consumed, and CUDA resumed with accepted
shares. Evidence:
`target/core-update-audit/core32-locator-fork-live-verification.json`.
