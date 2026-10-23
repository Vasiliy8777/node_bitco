# RVN asset reference scope

Source revision: Ravencoin Core `6d48ae0175b10283248146ae3080e2ba70966739`.

- [Asset constructors and metadata rules](https://github.com/RavenProject/Ravencoin/blob/6d48ae0175b10283248146ae3080e2ba70966739/src/assets/assets.cpp)
- [Serialized fields and metadata hashes](https://github.com/RavenProject/Ravencoin/blob/6d48ae0175b10283248146ae3080e2ba70966739/src/assets/assettypes.h)
- [Asset name assertions](https://github.com/RavenProject/Ravencoin/blob/6d48ae0175b10283248146ae3080e2ba70966739/src/test/assets/asset_tests.cpp)
- [Script classification](https://github.com/RavenProject/Ravencoin/blob/6d48ae0175b10283248146ae3080e2ba70966739/src/script/script.cpp)

`generate-name-vectors.ps1` extracts 124 literal `IsAssetNameValid` assertions
from the pinned Core test suite into committed `asset-names.tsv`. These are
Core's expected test results, not outputs of running a C++ oracle. Tests run
offline; only regeneration needs network access. Reference tests are copyright
the Raven Core developers and distributed under Core's MIT license.

Run regeneration from the repository root:

```powershell
./rvn-chain/reference/generate-name-vectors.ps1
```

The Java codec accepts the canonical P2PKH envelopes emitted by the four Core
constructors (`rvnq`, `rvno`, `rvnt`, `rvnr`). Its fixed hexadecimal fixtures are
manually assembled format examples, not exported mainnet transactions. It
separates wire decoding from name/amount/state rules. Strict decoding is **not**
a full consensus acceptance policy: historical noncanonical pushes, ignored
trailing bytes, pre-KawPoW transfer offsets, and null qualifier/restriction
scripts still need era-dependent handling and accepted-block fixtures.

Metadata rules cover immutable issuance/reissuance state transitions only.
They do not check burns, owner authorization/change, asset conservation,
restricted verifier expressions, or persistent/atomic asset undo. The native
replay asset-activation stop remains in place. Do not strip the asset suffix
before signature hashing: the original script bytes must remain committed.
