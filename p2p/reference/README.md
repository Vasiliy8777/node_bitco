# Bitcoin Core eviction reference

`eviction-reference.cpp` runs the unchanged Bitcoin Core v32.1
[`src/node/eviction.cpp`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/node/eviction.cpp).
Only dependency declarations are stubbed by the generation script. Expected
answers are produced by Core, independently of the Java implementation.

Run `./p2p/reference/generate-eviction-vectors.ps1` from PowerShell with Visual
Studio C++ build tools installed. The script downloads the pinned source and
builds the oracle under `p2p/target/core32-eviction-reference`. The reference
source SHA-256 is
`8ef03ed1499e08fa32de824b908f7529b74943d98e64b40bca130d19a3980ac6`.

The 192 deterministic selection/protection cases are stored in
`p2p/src/test/resources/core32-reference/eviction-vectors.tsv`. Ordinary Maven
tests consume this fixture offline and need neither a C++ compiler nor Core.
Cases exercise unsigned netgroup keys, privacy-network ratios, connection ages,
ping and usefulness protections, preferred eviction, outbound and NoBan peers.
Fixture ordering avoids unspecified C++ tie choices at protection boundaries.
Java preserves the same comparator equivalence classes; stable sorting can
choose a different equivalent peer when every compared field ties.

Core's MIT license is included in the p2p artifact under
`META-INF/licenses/bitcoin-core-MIT.txt`.
