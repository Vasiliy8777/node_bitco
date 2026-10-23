# Historical PoW reference fixtures

`generate-vectors.ps1` downloads the C hash implementations from Ravencoin Core
commit `6d48ae0175b10283248146ae3080e2ba70966739` into the ignored `target/core-reference`
directory and compiles `x16-reference.c` with MSVC x64. The wrapper follows the
16-round and zero-padded Tiger rules in Core `src/hash.h`; it is deliberately
separate from the Java production implementation.

Run from the repository root:

```powershell
./rvn-pow/native/reference/generate-vectors.ps1
mvn -pl rvn-app -am test
```

MSVC and network access are required to regenerate fixtures only. Normal Maven
tests use the committed `src/test/resources/core-reference/x16-vectors.tsv` and
require neither C compilation nor a running Core node.

There are 80 primitive vectors (16 algorithms, five message lengths), five
Tiger192 vectors and 40 synthetic full-header chain vectors. The chain fixtures
cover every algorithm repeated sixteen times plus mixed algorithm sequences.
They are computational known answers, not claims that synthetic headers are
valid blocks. A separate test checks the published Ravencoin mainnet genesis.

Reference source:
https://github.com/RavenProject/Ravencoin/blob/6d48ae0175b10283248146ae3080e2ba70966739/src/hash.h

Vendored Java provenance and licensing are in `../../SPHLIB_PROVENANCE.md`.
