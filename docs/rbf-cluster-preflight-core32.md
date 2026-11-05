# RBF cluster preflight and RPC rejection — Core 32.1

References: [Core replacement validation](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp#L917-L936),
[chunk calculation guard](https://github.com/bitcoin/bitcoin/blob/v32.1/src/txmempool.cpp#L961-L969).

Single-transaction RBF previously built both feerate diagrams before enforcing
the prospective cluster count/size limits. The outer admission would reject the
oversized candidate later, after unnecessary optimizer work. The replacement
handler now checks prospective cluster limits immediately before diagram work,
after the existing fee checks. TRUC checks retain their separate existing order.
The isolated candidate cannot modify the live map on rejection.

Two policy regressions cover replacement joining two 32-transaction chains
(65 resulting transactions) and sigop-adjusted cluster-size overflow. Both
failed before the guard because the replacement handler returned without
rejecting the prospective graph. IntelliJ on November 5 at 19:04 Bangkok showed
the old handler at the diagram call with after.size=65 and clusterCount=64.
Expression/stack evidence had no breakpoint errors; agent breakpoints removed.
Baseline: `target/core-update-audit/core32-rbf-cluster-preflight-before.log`.

The native Core fixture also constructs two real, signed 32-transaction chains
and a conflicting replacement spending both tips. Every prerequisite is
accepted by both Core and Java. Core rejects the replacement as too-large-cluster;
Java formerly exposed its internal cluster-count-limit through testmempoolaccept.
That independent comparison failed at the exact rejection-name assertion.
Baseline: `target/core-update-audit/core32-rbf-native-before.log`.

The single-transaction testmempoolaccept RPC boundary now translates the two
internal count/size reasons to Core's too-large-cluster name. Internal policy
causes remain separate. Authenticated RPC cases cover both names, and the native
test compares allowed/reject-reason and verifies the Java pool is unchanged.
IntelliJ at 19:15 captured the old RPC dispatch forwarding cluster-count-limit;
the RPC-only baseline is `target/core-update-audit/core32-cluster-rpc-before.log`.

All mempool tests passed after the preflight guard. Full Bitcoin/RVN packaging
and native comparisons are recorded separately. This does not port Core's
budgeted optimizer or establish every RPC rejection/schema equivalence. Other
replacement/eviction graph work remains tracked under the eight-direction audit.

## Cluster weight boundary

Core constructs its graph with a cluster-size bound of configured vbytes times
four and inserts sigop-adjusted transaction weights:
[construction](https://github.com/bitcoin/bitcoin/blob/v32.1/src/txmempool.cpp#L161-L167),
[staged weight](https://github.com/bitcoin/bitcoin/blob/v32.1/src/txmempool.cpp#L971-L983).
The Java cluster-size check previously summed rounded per-transaction vsize.
It now sums adjusted weight exactly and applies a single ceiling division for
the cluster limit. Individual vsize, fee/eviction accounting and TRUC size checks
retain their existing semantics.

Four scaled graph-boundary cases cover weights 401+401 and 401+402 at a 201-vbyte
limit, overflow 403+402 and exact integral 400+400. Two allowable cases failed
before this change. IntelliJ on November 5 at 19:24 captured the old calculation
as 202 versus limit 201 for the two-entry fixture; stack/expression had no errors.
Baseline: `target/core-update-audit/core32-cluster-weight-before.log`.
These are graph-level unit cases derived from Core's weight-unit contract, not
a claim of a native signed-transaction rounding-boundary test.

The RBF/RPC full package run passed 5,195 cases (5,183 passed, 12 opt-in skips,
zero failures/errors) at 19:23:13 Bangkok, before this final weight correction:
`target/core-update-audit/core32-rbf-cluster-preflight-full-build.log`.
Final weight-corrected full verification is recorded separately.

Final Bitcoin/RVN packaging succeeded November 5 at 19:32:26 Bangkok on the
weight-corrected source: 5,199 total cases, 5,187 passed, 12 opt-in skips, zero
failures/errors. Both applications packaged. It includes the final graph weight
boundaries, both RPC count/size names and the native signed 65-transaction
replacement comparison plus existing fee/cluster/PRESYNC reference checks.
Log: `target/core-update-audit/core32-rbf-weight-final-full-build.log`.

Live verification on November 5 at approximately 19:34 Bangkok: original IDEA
`BitcoinNodeApplication GPU`, exactly one Bitcoin process (PID 129184), RUNNING,
height/headers 974668, IBD false, eight connections including two block-relay
peers. Mempool held 23,428 transactions / 8,209,411 virtual bytes. Two anchors
loaded; CUDA accepted shares at approximately 2.32 GH/s. Spring initialization
was 7.041 seconds. Evidence:
`target/core-update-audit/core32-rbf-weight-live-verification.json`.
