# Serving eligible side-chain blocks

Implemented 2026-11-02 against Bitcoin Core 32.1's
[`BlockRequestAllowed`, `ProcessGetBlockData` and `CanDirectFetch`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp).

GETDATA now uses a dedicated `NodeValidationService.requestedBlock` snapshot.
The candidate's eligibility, body availability and compact-response decision
are checked under the chain lock. Serialization and network writes remain on
the existing per-peer bounded outbound worker. The active-chain-only `findBlock`
lookup used elsewhere retains its existing semantics.

Active-chain candidates are eligible regardless of age. Outside the active chain,
the same gate used for direct header requests requires script-validation status,
no failed ancestry, timestamp age below 30 days relative to the best header and
proof-equivalent age strictly below 30 days. A stored unvalidated side-chain body
does not qualify. Missing/pruned bodies cannot be served even if their headers
are eligible.

Legacy MSG_BLOCK responses omit witness; MSG_WITNESS_BLOCK responses include it.
MSG_CMPCT_BLOCK may return a compact block only when the candidate's height is
at least active height minus five and the active-tip timestamp is strictly newer
than now minus twenty target spacings. Other eligible compact requests receive
a full witness block. The height comparison also applies to validated former
tips outside the active chain, as in Core.

Unknown, pruned and ineligible block requests are silently ignored. Unknown
inventory types are consumed without generating NOTFOUND. Missing transaction
requests from a transaction-relay peer still receive NOTFOUND; the accumulated
response no longer incorrectly includes block hashes. Existing request budgets,
yielding between block responses and slow-peer isolation remain in force.

Tests cover an actual reorganization followed by legacy/witness/compact requests
for the former tip, unvalidated side-chain bodies, unavailable bodies with retained
headers, unknown block hashes/types, transaction-only NOTFOUND, a stale active tip
falling back to a full witness block, and exact compact age/depth boundaries.
Existing header eligibility, failed-branch and 30-day limit tests are retained.

## Remaining limits

The subsequent [GETBLOCKTXN phase](blocktxn-serving-core32.md) removes that path's
active-chain-only restriction and adds its distinct height/fallback rules. This phase does not
add Core's recent-block cache, raw on-disk witness-block fast path, historical
upload target or Bloom-filtered
block support. It does not establish complete Core equivalence or full historical
mainnet replay. Validation and storage scheduling retain their documented limits.
The later [limited-history serving phase](limited-history-serving-core32.md)
adds the advertised NODE_NETWORK_LIMITED serving/disconnection rule.

The original single `BitcoinNodeApplication GPU` IDE launch is retained. The node
is stopped before compilation and restarted only after packaging/checks; no
alternate deployment JAR is introduced.

Focused verification passed all 65 cases, with zero failures/errors/skips:
`target/core-update-audit/core32-side-block-serving-focused.log`. This includes
the native Core 32.1 block-acceptance matrix (77 scenarios inside one JUnit case),
relay/compact/header processing, write deadlines and the new serving cases.

Full Bitcoin/RVN verification passed 5,085 total cases: 5,073 passed, 12 opt-in
skips and zero failures/errors. Both applications packaged successfully. Log:
`target/core-update-audit/core32-side-block-serving-full-build.log`.

Live verification at 22:18 Asia/Bangkok: original IDE configuration, PID 73408,
RUNNING, exactly one Bitcoin node. Authenticated RPC reported height/headers
974269, IBD false, eight connections including two block-relay peers. The two
saved anchors were consumed; CUDA resumed at approximately 2.55 GH/s with nine
accepted shares. Evidence:
`target/core-update-audit/core32-side-block-serving-live-verification.json`.
