# Ravencoin PoW — KawPoW calculation building blocks

Input: `node_bitco-main - 2026-10-23T224749.097.zip`.

Includes actual 22-round Keccak-f[800] permutation, unsigned 32-bit ProgPoW arithmetic,
KawPoW 7500-block epoch indexing, a 120-byte header field view, and a 16-lane
mix reduction utility. All are isolated in the existing `rvn-pow` module.

## Crucial scope
This is **not** the full KawPoW verifier. No DAG/cache generation, programmable
random math sequence, seed/final hash, or Core-validated header vectors are
provided. X16R is also incomplete (14 primitives missing), as is X16RV2
(Tiger192 and the same primitives missing). Do not use these classes alone
to validate blocks.

## Testing
`mvn -pl rvn-pow -am test`
`mvn -pl rvn-app -am test`

Compiled production sources against the current source ZIP with JDK 21;
full Maven/JUnit not run in this environment.
