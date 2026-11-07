# TRUC direct-conflict exception after reorg — Core 32.1

Reference: [`SingleTRUCChecks`, child_will_be_replaced](https://github.com/bitcoin/bitcoin/blob/v32.1/src/policy/truc_policy.cpp).

A reorg can leave a TRUC parent with multiple descendants. Core permits a new
TRUC child when it directly conflicts with any existing descendant of that
parent: the descendant-count check has a direct-conflict exception. This does
not require the resulting family to shrink to two transactions. Unrelated
conflicts do not qualify, and fees, diagram improvement, version inheritance,
size and ancestor limits still apply.

Java previously checked only the prospective graph after removing conflicts.
It therefore rejected a valid replacement if other grandfathered descendants
remained. The single-transaction admission path now supplies the pre-replacement
entries and direct conflicts to the graph-limit check. Only a direct conflict
inside that parent's old descendant set waives its original descendant-count
check. The original count plus the new child is checked even when removal
of an unrelated conflict indirectly shrinks the final family. No exception
is stored on the parent, and the context does not apply to
the multi-transaction package-admission path.

Real mempool/UTXO tests create the family through `reconcile`: three version-3
children first spend confirmed outputs, then their version-3 parent is
resurrected. A higher-fee direct replacement removes the old conflicting child
while preserving the parent and its two other children. Negative cases cover:
a fresh spend of another parent output; an unrelated direct conflict; an
insufficient replacement fee; a mixed-version replacement; and indirect removal
of all descendants by replacing a different grandfathered parent. Rejections
preserve the original pool contents.

This implements the direct-conflict descendant-count exception. Independent
native reorg comparisons of these exact scenarios, the full TRUC/package-RBF
matrix and the budgeted graph optimizer remain open. The general native Core
compatibility suite does not by itself verify the new reorg fixture.

Validation, November 6, 2026: the initial focused run passed all 520 mempool
cases before the final version-inheritance and indirect-removal regressions
were added. The final source passed 5,235 full-reactor cases (5,223 passed,
12 opt-in skipped, zero failures/errors), including all five new reorg/RBF
cases, native Core 32.1 compatibility and both application packages. Final log:
`target/core-update-audit/core32-truc-replacement-final-full-build.log`, completed
10:24:43 Bangkok. The earlier `core32-truc-replacement-full-build.log` does not
include the final original-count/indirect-removal correction.

Original IDEA `BitcoinNodeApplication GPU` restarted from `app/target/classes`,
mainnet/gpu, as PID 125584. RUNNING was observed at 10:25:24; the RPC check
reported height/headers 974754/974754, IBD=false, eight peers (two block relay),
26,672 mempool entries, exactly one Bitcoin process and no privileged peer.
Evidence: `target/core-update-audit/core32-truc-replacement-final-live-verification.json`
and PID-specific records in `target/mainnet-node.log`.
CUDA accepted shares were observed again at 10:26.
