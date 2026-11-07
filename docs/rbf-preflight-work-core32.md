# RBF conflict preflight and connectivity work — Core 32.1

Reference: [`GetEntriesForConflicts`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/policy/rbf.cpp)
checks the number of distinct clusters before collecting their descendants.
[`PackageRBFChecks`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp)
checks package shape and existing parents before that bound.

The Java helper previously rebuilt full-pool adjacency for each conflicting
component. With k independent conflicts in n entries this required O(k*n)
connectivity setup, even though the component bound is only 100. The helper now
builds adjacency once per check, visits each relevant component at most once,
and returns immediately when the count exceeds the bound. Setup/traversal is
O(n+e), with no graph construction for an empty conflict set. Eligible
replacements retain a defensive late recheck; this is not a persistent cluster
index or the native Core optimizer.

Single-transaction admission now checks the bound after conflict/sibling
discovery and before descendant collection. Package admission checks its
one-parent/one-child context, existing mempool parents and cluster bound before
collecting descendants or creating the staged pool. Package-fee, diagram and
prospective cluster checks still run for eligible candidates. Unsupported
multi-transaction testmempoolaccept replacement remains rejected before this
preflight.

Five regressions cover 200 seeded DAGs against an independent union-find
component oracle; a deterministic full-map read budget on 10,000 unrelated
entries and 101 conflicts; no reads for empty conflicts; and actual single
and package admissions with 101 conflicting clusters. The latter two use an
instrumented UTXO view to prove rejection before coin loading and verify the
original pool snapshot and sequence are unchanged. Existing 100/101 and
120-transaction replacement cases remain the acceptance checks.

The local preflight is earlier than full UTXO/script validation. This preserves
the candidate-bound rule but does not establish identical error precedence for
submissions violating multiple rules. Native error classification, persistent
cluster indexing, the complete package matrix and Core's bounded spanning-
forest optimizer remain open. The general native suite is not a new independent
wire comparison of these work-budget fixtures.

Validation, November 6, 2026: all 535 mempool cases passed in the focused run.
The full Bitcoin/RVN reactor passed 5,248 cases (5,236 passed, 12 opt-in skipped,
zero failures/errors), including all five new cases, native Core 32.1
compatibility and both application packages. Log:
`target/core-update-audit/core32-rbf-preflight-work-full-build.log`, completed
12:16:30 Bangkok. Original IDEA `BitcoinNodeApplication GPU` restarted from
`app/target/classes`, mainnet/gpu, as PID 143276. RUNNING at 12:17:09 was followed
by RPC height/headers 974764/974764, IBD=false, nine peers (two block relay),
23,005 mempool entries, accepted CUDA shares, one Bitcoin process and no
privileged peer. Evidence:
`target/core-update-audit/core32-rbf-preflight-work-live-verification.json` and
PID-specific records in `target/mainnet-node.log`.
