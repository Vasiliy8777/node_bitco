# Bitcoin Core v32.1 mempool references

Production uses budgeted SFL/PostLinearize, retained cluster quality/orders and
main/staging snapshots. The old maximum-closure solver is test-only. Independent
C++ references compile pinned Core source; see `CORE-LICENSE.txt`.

| Generator | Independent assertions |
| --- | --- |
| `generate-budget-vectors.ps1` | 9600 exact orders, costs and optimal results; genuine Core xoroshiro128++/RandomMixin; five budgets and five modes including continuation and topology repair. |
| `generate-txgraph-vectors.ps1` | 3600 fully optimized main/staging snapshots, ancestry, descendants, components, mining/worst chunks, fee changes and commit/abort. Outer RNG deterministic; allocator stubbed. |
| `generate-memory-vectors.ps1` | 3600 graph snapshots with genuine unchanged Core memusage.h and release x64 allocator rounding. Unrelated container declarations are not instantiated. |
| `generate-trim-vectors.ps1` | 512 cluster groups joined by deferred dependencies; exact Trim removals under count/weight limits and Core SanityCheck. |
| `generate-diagram-vectors.ps1` | 383 fully optimized main/staging RBF diagrams, including cancellation of an unchanged component. Budgeted continuation has its own exact SFL reference. |

Native `BitcoinCoreMempoolScriptMatrixTest` additionally compares complete RPC
responses and pool membership under strict script/policy rules, partial package
acceptance, client caps, TRUC, memory pressure, fee deltas, RBF and oversized
reorgs. A successful probe does not trim; failed real admission may still remove
a weaker old entry. See [current completion status](../../docs/mempool-core32-completion.md).

## Historical unlimited/cache baseline

The remainder records the earlier independent unlimited oracle and incremental
implementation stages. Statements about unfinished production work below are
historical; the table and completion report above describe the current code.

`generate-cluster-vectors.ps1` compiles the unchanged v32.1
[`cluster_linearize.h`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/cluster_linearize.h),
fee arithmetic, bitsets and deque headers. The harness invokes Core's
`Linearize` and `ChunkLinearizationInfo`, with an explicit index fallback order.
The run must report `optimal=true`; a budget-limited result is never accepted.

Platform annotations, assertions, allocation statistics and the random number
generator have standalone shims. The RNG shim supplies `std::mt19937_64` rather
than linking Core's RNG. This changes the optimization path and cost, but the
oracle still runs Core's optimizer and minimizer to certified optimality. The
stored cases test optimal results, not Core's budget-limited intermediate states.

Regeneration requires PowerShell, network access and Visual Studio C++ build
tools. Run `./mempool/reference/generate-cluster-vectors.ps1`. Compilation and
downloaded headers stay under `mempool/target/core32-cluster-reference`.
The source header SHA-256 is
`c193753b288c587ae276b2657372ac64ed541bb6f5f51680b80cb2ba5667391b`.
All dependency hashes are recorded in the fixture header.

The 384 reproducible cases live in
`mempool/src/test/resources/core32-reference/cluster-vectors.tsv`. Normal Maven
tests need neither Core, a compiler nor network access. Cases include empty and
singleton sets, chains, shared-parent stars, dense and sparse DAGs, equal and
zero rates, negative modified fees and cross-products exceeding signed 64-bit
arithmetic. Both transaction order and minimal chunk fees, weights and members
are compared exactly under the common fallback order.

Java uses an exact fractional maximum-closure solver with residual-component
chunk minimization. Core uses a budgeted spanning-forest solver. These are
different implementations; the reference tests establish agreement on fully
optimized outputs for these graphs. The harness explicitly uses an integer-index
fallback as a common test comparator. This is not Core mempool's production
fallback: CTxMemPool supplies a txid comparator. Production Java now uses
unsigned serialized txid bytes, equal-rate prefix weight and the maximum txid
of a chunk in the shared graph/mining key. See
[`equal-rate-fallback-core32.md`](../../docs/equal-rate-fallback-core32.md).
Eviction selects the reverse of that key;
[`scope and pressure-test limits`](../../docs/equal-rate-eviction-core32.md).
Budget-limited optimization and incremental TxGraph behavior remain separate
work; the oracle's index comparator must not be described as production ordering.

The main pool now maintains persistent parent/child adjacency and connected
components, with a bounded revision-based chunk cache. Bulk commits now reconcile
the graph by txid delta; initial staged-pool population copies ready adjacency
and spent indexes into independent mutable structures without rescanning inputs.
This does not implement Core's complete
main/staging TxGraph or budgeted solver;
[`implementation and checks`](../../docs/persistent-main-graph-core32.md).
[`Bulk commit follow-up`](../../docs/graph-commit-delta-core32.md) preserves
unchanged components and checks arbitrary parent/child order and fee updates.
[`Staging copy checks`](../../docs/stage-graph-copy-core32.md) cover source/copy
isolation and public known-transaction probes without repeated input scans.
[`Spent-index commit checks`](../../docs/spent-commit-delta-core32.md) compare
delta publication against fresh spent/byte/diagram oracles and verify that
known-only package commits do not scan unrelated transaction inputs.
[`Indexed query checks`](../../docs/indexed-graph-queries-core32.md) compare full
graph views against input-based oracles and verify once-per-component batch
linearization. Conflict descendant closure now also uses persistent adjacency.
