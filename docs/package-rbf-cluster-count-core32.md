# Package RBF conflict-cluster bound — Core 32.1

References: [`GetEntriesForConflicts`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/policy/rbf.cpp)
and [`PackageRBFChecks`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp).
Both single-transaction and package RBF use the same helper in Core: the
100-candidate bound counts distinct directly conflicting clusters, not the
total number of transactions removed.

Single-transaction Java RBF already counted clusters, but package RBF still
summed each conflict's descendant count. That could reject valid packages
evicting more than 100 transactions from only a few clusters, and could count
overlapping descendant sets repeatedly. The cluster-count check is now shared
between both paths; the package path retains its existing internal error name.

Real mempool/UTXO regressions cover:

- Two independent 60-transaction chains replaced by a valid one-parent/one-child
  package: all 120 old transactions are removed and only the package remains.
- Exactly 100 distinct root clusters replaced by the two-transaction package.
- 101 distinct clusters rejected, preserving both pool membership and sequence.

Fee payment, strict diagram improvement, package topology, no-mempool-ancestor
restriction and prospective cluster limits still run. No replacement or trust
options were enabled in production.

This fixes the count's unit and deduplication semantics. It does not implement
Core's bounded optimizer, the full package acceptance/RBF matrix or a new native
memory-pressure/wire fixture for the 120-transaction scenario. Those remain
separate verification work; the general native compatibility suite is not
claimed as an independent comparison of this new fixture.
Subsequent update: [early preflight and single-build connectivity](rbf-preflight-work-core32.md)
now avoid descendant collection/staging for over-bound candidates and repeated
adjacency rebuilding. Persistent indexing and the full optimizer remain open.

Validation, November 6, 2026: all 530 mempool cases passed in the focused run.
The full Bitcoin/RVN reactor passed 5,243 cases (5,231 passed, 12 opt-in skipped,
zero failures/errors), including the three new package cases, native Core 32.1
compatibility and both application packages. Log:
`target/core-update-audit/core32-package-rbf-clusters-full-build.log`, completed
11:58:14 Bangkok. Original IDEA `BitcoinNodeApplication GPU` restarted from
`app/target/classes`, mainnet/gpu, as PID 108060. RPC verified height/headers
974762/974762, IBD=false, eight peers (two block relay), 23,605 mempool entries,
one Bitcoin process and no privileged peers; accepted CUDA shares were observed.
Evidence: `target/core-update-audit/core32-package-rbf-clusters-live-verification.json`
and PID-specific entries in `target/mainnet-node.log`.
