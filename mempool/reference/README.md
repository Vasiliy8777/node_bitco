# Bitcoin Core cluster linearization reference

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
optimized outputs for these graphs. Live fallback order uses transaction hashes
in this application and can differ from Core's transaction index order.
