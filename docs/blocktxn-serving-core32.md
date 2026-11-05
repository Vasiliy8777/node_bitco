# GETBLOCKTXN height and fallback behavior

Implemented 2026-11-02 against Bitcoin Core 32.1's
[`GETBLOCKTXN` and `SendBlockTransactions`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp).

The previous GETBLOCKTXN path required active-chain membership. It now asks
`NodeValidationService.requestedBlockTransactions` for a body/response snapshot
under the chain lock. If a stored block is at least active height minus ten,
the request may return its selected transactions without requiring active-chain
membership or script-validation status. This reproduces Core's height/data rule;
it does not admit that block to the active chain or bypass block validation.

For blocks below that inclusive height boundary, Core turns the request into
ordinary witness GETDATA. This implementation uses the same block eligibility
gate and returns a full witness block on the existing per-peer outbound worker.
An older unvalidated/ineligible side-chain body is ignored. Recent unknown or
pruned bodies are also ignored. These paths generate no NOTFOUND response.

An empty index list disconnects the peer before block lookup, including for an
unknown hash, without classifying the request as a protocol violation. For a
recent body, all requested indexes are checked before any
BLOCKTXN is sent; an out-of-range index disconnects the peer without partial
transaction output. Old full-block fallback does not inspect the transaction
indexes, as in Core. BLOCKTXN uses witness serialization (compact version 2).
The out-of-range recent index uses the peer's protocol-violation path rather than
being logged as a local outbound-worker error and closed as an ordinary session.

New tests create real competing branches and cover exact depth ten versus eleven,
recent unvalidated side bodies, ineligible old side-body suppression, full witness
fallback even when requested indexes exceed that old block's transaction count,
unknown/pruned bodies, empty indexes and invalid recent indexes. A later valid
request on the same outbound queue serves as a barrier to verify silent ignores.

## Remaining limits

Core's most-recent-block cache can answer before the disk/height path; this cache
and its exemption are not implemented. The fallback executes on the existing
bounded outbound worker rather than Core's GETDATA deque. Historical upload
targets, Bloom support and the previously
documented transport/RPC/replay limits remain outside this phase. Complete Core
equivalence is not claimed.
The later [limited-history serving phase](limited-history-serving-core32.md)
applies that window to old GETBLOCKTXN fallback as well.

The single original `BitcoinNodeApplication GPU` IDE launch is retained. Production
is stopped before compilation and restarted after checks; no alternate runtime
JAR is introduced.

Focused verification passed all 65 cases with zero failures/errors/skips:
`target/core-update-audit/core32-blocktxn-serving-focused.log`. This includes
the four new GETBLOCKTXN scenarios, existing relay/compact/body-policy checks,
write deadlines and the native Core 32.1 block-acceptance matrix (77 scenarios
inside one JUnit case).

Full Bitcoin/RVN verification completed at 00:00 on November 3: 5,089 total
cases, 5,077 passed, 12 opt-in skips, zero failures/errors. Both applications
packaged successfully. Log:
`target/core-update-audit/core32-blocktxn-serving-full-build.log`.

Live verification at 00:03 Asia/Bangkok on November 3: original IDE configuration,
PID 78176, RUNNING, exactly one Bitcoin node. Authenticated RPC reported height/
headers 974275, IBD false, five connections including two block-relay peers. The
two saved anchors were consumed, and CUDA resumed with accepted shares. Evidence:
`target/core-update-audit/core32-blocktxn-serving-live-verification.json`.
