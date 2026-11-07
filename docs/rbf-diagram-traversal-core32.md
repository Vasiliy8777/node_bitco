# RBF diagram traversal — observed live chainstate stall

During the TRUC/reorg batch's production verification on November 6, 2026,
PID 117008 received a new header while RPC requests exceeded their 10-second
timeout. An IDEA debugger-status check found no active debug session; the node
was launched in its original run configuration. A read-only `jcmd Thread.print`
captured the live runtime path without changing its launch or suspending it.

Evidence: `target/core-update-audit/core32-truc-reorg-runtime-threads.txt`.
The bitcoin-relay worker was RUNNABLE in `ClusterLinearization.valueAt` from
`compareDiagrams` / `MempoolGraphPolicy.replacement` / `Mempool.admitInternal`,
holding both the mempool and chainstate monitors. Its accumulated CPU time was
about 105 seconds. Other threads, including stratum template and lifecycle
work, were waiting for that same chainstate monitor. This identified a hot
computation, rather than establishing a deadlock or slow block download.

The comparator previously rescanned each full diagram from the start at every
breakpoint. For n segments this could perform O(n²) segment visits while the
chainstate lock was held. The replacement cursor now advances monotonically
through each diagram. Segment traversal is O(n+m); the existing sorted union of
breakpoints still costs O((n+m) log(n+m)). Rational BigInteger interpolation,
every-prefix dominance, strict improvement, different diagram lengths and
constant-value tails preserve their previous semantics.

Checks: 2,000 seeded pairs match a separate pointwise reference evaluator;
identity cases, negative/large fees, differing lengths and empty diagrams are
covered. A 20,000-segment pair enforces a linear element-read budget, failing
early on repeated scans rather than relying on machine timing. This does not
claim to implement Core's budgeted spanning-forest optimizer: it removes the
observed traversal bottleneck in the existing exact comparator.

Validation on November 6: all 517 mempool cases passed in the focused run.
The final full Bitcoin/RVN reactor then passed 5,230 cases (5,218 passed,
12 opt-in skipped, zero failures/errors), including native Core 32.1
compatibility, all new TRUC and traversal tests and both application packages.
Log: `target/core-update-audit/core32-truc-diagram-final-full-build.log`, completed
01:50:46 Bangkok. This is the final build containing both corrections; the
earlier `core32-truc-reorg-full-build.log` predates the traversal fix.

Final production verification: original IDEA `BitcoinNodeApplication GPU`
restarted from `app/target/classes`, mainnet/gpu, as PID 135356. RUNNING at
01:51:38 was followed by successful processing through height/header 974692,
IBD=false, seven peers (two block relay), 32,246 mempool entries, one Bitcoin
process and no privileged peers. The final four-method RPC check completed in
about 1.7 seconds; this is an operational observation, not a controlled latency
benchmark. CUDA accepted shares at approximately 2.46 GH/s. Evidence:
`target/core-update-audit/core32-truc-diagram-final-live-verification.json` and
PID-specific records in `target/mainnet-node.log`.
