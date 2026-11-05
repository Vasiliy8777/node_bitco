# BIP35 mempool permission — Core 32.1

References: [`MEMPOOL` and `SendMessages` handling](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp),
[permission definitions](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_permissions.h),
and [Core 32.1 CFeeRate::GetFee](https://github.com/bitcoin/bitcoin/blob/v32.1/src/policy/feerate.cpp).

Explicit numeric inbound rules accept `mempool`. This right does not imply
Relay, ForceRelay, NoBan, Download, Addr or Bloom service. RPC reports it in the
effective permission list. Production permission rules remain unchanged.

A `mempool` request is authorized by locally granted Mempool permission or the
NODE_BLOOM service offered on that connection. Without either, an ordinary peer
is disconnected; a NoBan peer's request is ignored. NoBan by itself grants no
snapshot access. An authorized block-relay-only connection has no transaction
relay response. The configured node still does not advertise NODE_BLOOM or
implement BIP37 filtering; that separate permission remains open.

Authorized requests coalesce until the next per-peer inventory trickle. A first
request with no scheduled inventory deadline can be served on the next worker
tick; subsequent requests follow the deadline. NoBan bypasses the trickle wait,
matching Core's send-loop exception. This also applies to ordinary pending
transaction inventory. Explicit mempool responses do not require VERSION's
relay=true and can be served during IBD, while unsolicited transaction relay
remains suppressed during IBD.

The response snapshots current mempool entries at send time, clears redundant
pending announcements and includes known entries again. It sends MSG_TX or
MSG_WTX according to negotiated wtxid-relay, with at most 50,000 vectors in
each INV. Empty or completely filtered results send no empty INV. Each selected
transaction is marked known for subsequent ordinary announcement suppression.

BIP133 comparison uses base fee, excluding local mining fee deltas, and Core
32.1's **upward** fee rounding. BigInteger arithmetic avoids overflow for large
valid fee filters and vsize. This is deliberately not a comparison of the
entry's modified, rounded integer fee rate.

Local tests cover authorization and NoBan separation, offered NODE_BLOOM,
repeated known-transaction responses, relay=false, MSG_WTX, IBD, block-relay-only
exclusion, base-fee/delta and rounding boundaries, overflow, empty responses and
50,001 unique inventory entries split into 50,000 plus one. These exercise the
actual relay listener/worker and encoded messages using mocked network peers;
they are not an independent native Core wire matrix.

Remaining scope: BIP37 Bloom matching/update, native permission/scheduling
matrix, Core's upload-target accounting and GETDATA authorization by the last
announced mempool sequence. The existing local send budgets remain enforced;
they are not a substitute for Core's upload-target accounting. These limits
and the wider compatibility workstreams are not declared closed here.
The ordinary unsolicited INV path's modified-fee discrepancy was subsequently
fixed in [the BIP133 base-fee follow-up](ordinary-relay-base-fee-core32.md).
Both ordinary INV and the explicit BIP35 response now filter by base fee.

Validation on 2026-11-05: 46 focused cases passed, then all 5,224 full-reactor
cases completed (5,212 passed, 12 opt-in skipped, zero failures/errors), including
native Core 32.1 compatibility and both application packages. Full build log:
`target/core-update-audit/core32-mempool-permission-full-build.log`, completed
23:54:25 Bangkok. The original `BitcoinNodeApplication GPU` IDEA configuration
restarted from `app/target/classes`, mainnet/gpu, as PID 130884. RUNNING was
observed at 23:54:58; RPC height/headers 974686/974686, IBD=false, five connections
(two block relay), 31,848 mempool transactions and accepted CUDA shares were
verified at 23:55. There was exactly one Bitcoin process and no locally
privileged peer. Live evidence:
`target/core-update-audit/core32-mempool-permission-live-verification.json` and
PID-specific records in `target/mainnet-node.log`.
