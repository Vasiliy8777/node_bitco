# TRUC inheritance after reorg — Core 32.1

References: [`SingleTRUCChecks` / `PackageTRUCChecks`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/policy/truc_policy.cpp)
and [bypass-limits resurrection](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp).

Core can retain a mixed-version mempool edge after a reorg. Normal admission
checks the new transaction's direct parents for TRUC inheritance; it does not
reapply inheritance checks to every existing ancestor. In particular,
`SingleTRUCChecks` returns after inheritance checks for a non-TRUC transaction.

The Java check previously walked every ancestor, rejecting a new version-2
child of an existing version-2 parent if a resurrected version-3 ancestor had
created a grandfathered mixed-version edge further up the chain. It could also
reject by rechecking that ancestor's now-excessive descendant count.

`MempoolGraphPolicy.checkTrucLimits` now checks inheritance on new direct edges.
For a new TRUC transaction it retains the 10,000-vB limit, ancestor/two-generation
limit, 1,000-vB child limit and prospective parent descendant limits. It does
not revalidate historical ancestors' own size/inheritance policy. Cluster
limits, scripts, consensus, fees and RBF diagram checks remain separate.

The regression uses the real mempool/UTXO validation path: admit a version-2
transaction spending a confirmed output; resurrect its version-3 ancestor via
`reconcile`; then admit its version-2 child. All three remain present. A further
new version-3 child is rejected for mixed-version inheritance without mutating
the pool. A separate real admission test still rejects a third TRUC generation.
Existing tests cover sibling replacement and the opposite reorg version mix.

This fixes the ancestor-revalidation false rejection, not the complete TRUC/RBF
matrix. Independent native reorg wire comparison of this specific scenario,
replacement exceptions when a reorg leaves multiple descendants, and the
budgeted graph optimizer remain open. The full native compatibility suite is
separate evidence and does not establish this new scenario's native equivalence.

Subsequent update: the multiple-descendant direct-replacement exception is now
implemented with pre-replacement graph context; see
[implementation, tests and remaining scope](truc-reorg-replacement-core32.md).

Validation, November 6, 2026: the mempool-focused run passed all 513 mempool
cases before the third-generation regression was added. The TRUC batch source
then passed 5,227 full-reactor cases (5,215 passed, 12 opt-in skipped, zero
failures/errors), including all five reorg/TRUC cases, native Core 32.1
compatibility and both application packages. Full log:
`target/core-update-audit/core32-truc-reorg-full-build.log`, completed at
01:38:06 Bangkok. The original IDEA GPU configuration restarted from
`app/target/classes`, mainnet/gpu, as PID 117008.
That intermediate process reached RUNNING, but its live verification exposed
an independent quadratic RBF diagram traversal holding the chainstate lock.
It was gracefully stopped before further compilation; see
[runtime evidence and the additional fix](rbf-diagram-traversal-core32.md).
Its partial live snapshot is not a completed healthy synchronization check.
The subsequent final build includes both the TRUC and traversal corrections:
5,230 full-reactor cases (5,218 passed, 12 skipped), zero failures/errors,
completed November 6 at 01:50:46 Bangkok. Log:
`target/core-update-audit/core32-truc-diagram-final-full-build.log`.
