# Conflict-free mempool admission and restoration work

Reference: [Core graph staging and indexed parent/child access](https://github.com/bitcoin/bitcoin/blob/v32.1/src/txmempool.cpp).

Observed startup bottleneck: ordinary/restored admission rebuilt every neighbor
list, copied the complete pool, rebuilt its spent index and summed global size
for each transaction. The previous live startup took approximately 325 seconds;
the main thread was observed restoring persisted transactions in graph policy.

The new conflict-free path checks the prospective connected component using
existing spent-outpoint child edges and transaction parent edges. Only after
all validation and cluster/TRUC checks, and when capacity does not need eviction,
it appends the entry and updates its spent outpoints and virtual-byte total.
Package capacity trimming retains its existing commit boundary. Conflicting
replacement, eviction and capacity-overflow paths retain staged full-pool checks.

The virtual-byte total is maintained on append/removal and rebuilt alongside
the spent index on bulk commits. Revalidation recalculates it from retained
entries to respect original sigop/weight metadata. Fee-floor decay uses this
total. Transaction ordering, arrival time, admission height, unbroadcast metadata
and mutation sequence semantics remain unchanged.

Baseline allocation test: restoring 2000 independent real policy-validated
transactions allocated 456,671,400 bytes and failed the 256 MiB limit. IntelliJ
on November 5 at approximately 18:34 Bangkok confirmed the old limit check was
given all 1001 entries while adding one independent transaction, from
`admitRestored -> admitInternal -> checkLimits:87`. The expression and stack were
captured without breakpoint errors. Agent breakpoints were removed. Baseline:
`target/core-update-audit/core32-mempool-admission-before.log`.

After the change, the allocation test passes the same limit and verifies all
2000 accepted entries, restored metadata and spent-index lookups. An independent
whole-graph traversal is compared against indexed component discovery on 400
seeded DAGs, including multiple parents, joining branches, existing children of
a restored parent, mixed TRUC versions, count/size limits and rejection reasons.
All mempool-module/reactor-dependency tests passed, including existing Core C++
cluster vectors, replacement, expiry, package and reorganization tests. Log:
`target/core-update-audit/core32-mempool-admission-focused.log`.

This repairs the observed graph/copy cost, not the whole mempool workstream.
The budgeted spanning-forest optimizer, equal-rate fallback and exact bounded
Core intermediate results remain open. RBF, eviction and some TRUC prechecks
can still perform full-pool work. Virtual-byte accounting is the existing Java
limit model, not a byte-identical reproduction of Core memory allocation.

The production component walk also stops at the first cluster-count overflow,
before exploring every cluster an oversized addition tries to join. Count-limit
precedence over size/TRUC remains the same; all 400 policy comparisons exercise
the capped path as well as the independent complete-component comparison.

Full Bitcoin/RVN packaging passed November 5 at 18:46:14 Bangkok: 5,191 total
cases, 5,179 passed, 12 opt-in skips, zero failures/errors, with native Core RPC/
fee and PRESYNC wire comparisons. Log:
`target/core-update-audit/core32-mempool-admission-full-build.log`.
This full run precedes the final traversal-cap refinement. After that refinement,
all 506 mempool cases plus reactor dependencies passed without skips/failures/
errors; the two applications were then repackaged with tests skipped. Logs:
`target/core-update-audit/core32-mempool-admission-final-tests.log` and
`target/core-update-audit/core32-mempool-admission-final-package.log`.

Live verification on November 5 at approximately 18:50 Bangkok: original IDEA
`BitcoinNodeApplication GPU`, exactly one Bitcoin process (PID 127816), RUNNING,
height/headers 974664, IBD false, seven connections including two block-relay
peers. Mempool contained 24,180 transactions / 8,930,818 virtual bytes after
startup, confirming the pool was not simply left empty. Two anchors loaded and
CUDA resumed accepted shares (approximately 2.21 GH/s at the sample).
Evidence: `target/core-update-audit/core32-mempool-admission-live-verification.json`.

Observed Spring initialization was 8.534 seconds versus 324.841 seconds on the
previous startup. These are operational observations with potentially different
pool contents/cache conditions, not a controlled latency benchmark or guarantee.
The fixed-workload allocation test is the independent performance regression.
