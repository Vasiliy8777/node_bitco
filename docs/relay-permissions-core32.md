# Relay and ForceRelay follow-up — Core 32.1

References: [permission inheritance](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_permissions.h),
[TX/INV handling, RejectIncomingTxs and SendFeeFilter](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp),
and [TxDownloadManagerImpl::ReceivedTx](https://github.com/bitcoin/bitcoin/blob/v32.1/src/node/txdownloadman_impl.cpp).

Explicit numeric inbound CIDR rules now accept `relay` and `forcerelay`.
ForceRelay includes Relay; neither includes NoBan, Download, Addr or manual
connection status. RPC reports the effective rights in Core's permission order.
No production rights or mode changes were enabled.

`bitcoin.p2p.blocksonly` defaults to false. When enabled, automatic/manual
outbound clients advertise relay=false. An inbound connection advertises
relay=true when locally assigned Relay, otherwise relay=false. Transaction
messages and transaction inventory from an ordinary full-relay connection close
that connection in this mode, including during IBD; NoBan does not substitute
for Relay. Block-only inventory remains allowed. Relay permits incoming
transactions in this mode but does not bypass the IBD transaction guard.

Relay peers bypass candidate announcement count limits, including the local
per-hash source cap. Duplicate announcements still coalesce. Local inbound
queue budgets, global/per-peer in-flight windows and expiry remain bounded.
Those windows are implementation resource limits; this change does not claim
the entire download scheduler reproduces Core's delay/overload model.

New unsolicited transactions now reach ordinary mempool admission, just as
Core's ReceivedTx does, without requiring an outstanding GETDATA. They still
require validation; no permission grants consensus or policy exemptions.
Known duplicates skip re-admission. ForceRelay requeues an existing validated
mempool transaction with matching wtxid for announcement; a supplied alternative
witness cannot replace that validated object. ForceRelay peers receive no
outgoing feefilter. Their received fee filter continues to govern announcements
sent to them.

Manual connections were unintentionally excluded from the global inventory
backlog's targets even though their role allows transaction relay. They now
receive inventory through the same role predicate as the later send stage.
Remote relay=false and known-inventory suppression remain respected.

The TX duplicate lookup uses the existing mempool hash index; the public
`mempoolEntry` accessor no longer copies/scans all entries for this lookup.

Checks cover explicit ForceRelay inheritance, ordinary versus privileged
announcement counts while retaining download windows, unsolicited admission,
invalid transaction rejection despite ForceRelay, validated duplicate relay to
a manual peer, the ordinary duplicate negative case, blocksonly rights
separation, IBD behavior and outgoing fee-filter suppression with an ordinary
peer proving that the scheduled pass ran.

Remaining scope: BloomFilter and Mempool permissions, native wire comparisons
for the complete permission matrix, tx-download scheduling equivalence,
recent-reject/reconsiderable caches and the broader error classification work
remain open. This follow-up does not close all compatibility directions.

Subsequent update: [Mempool permission/BIP35](mempool-permission-core32.md) now
implements the explicit request/response path. Bloom, upload-target accounting,
GETDATA sequence authorization and full native permission comparisons remain
open as detailed there.

Validation, 2026-11-05: 53 focused cases passed, then the final production
source passed 5,215 full-reactor cases (5,203 passed, 12 opt-in skipped, zero
failures/errors). This includes native Core 32.1 compatibility and both
application packages. Full log:
`target/core-update-audit/core32-relay-permissions-full-build.log`, completed
22:44:50 Bangkok. The original `BitcoinNodeApplication GPU` IDEA configuration
restarted as PID 131136 from `app/target/classes`, mainnet/gpu. RUNNING was
observed at 22:45:31; RPC height/headers 974681/974681, IBD=false, seven
connections including two block-relay peers and 29,899 mempool entries were
verified. Exactly one Bitcoin process and no privileged peers were present.
CUDA accepted shares at about 2.40 GH/s. Evidence:
`target/core-update-audit/core32-relay-permissions-live-verification.json` and
PID-specific entries in `target/mainnet-node.log`.
