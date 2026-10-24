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

`RvnAssetTransferChecks` adds conservation for canonical root/sub/unique/channel
transfers and their owner tokens. It checks metadata existence, units, owner
amounts and bounded totals; spent issuance/reissuance outputs are included.
`RvnAssetIssueChecks.verifyMainnet` checks canonical root/sub issuance layout,
exact mainnet burn output, matching owner creation and spent/returned immediate
parent owner for subassets, and returns an immutable metadata plan.
`RvnAssetReissueChecks.verifyMainnet` checks the final reissue output, exact
100 RVN burn, spent/returned owner token and conservation of existing transfers.
`RvnAssetChanges` carries immutable net before/after values; the storage module
can commit these atomically with native coins, shared undo and tip. This is a
storage primitive, not an accepted-block validation status.
These APIs are not wired into block admission: callers must classify every
script, resolve unique inputs, verify signatures and native values, and commit
metadata with UTXO/undo atomically. Other asset families, complete messaging
deployment handling and historical envelopes remain incomplete.
Burn constants and layout come from the pinned `src/chainparams.cpp` and
`CTransaction::VerifyNewAsset` in `src/assets/assets.cpp`; conservation follows
`Consensus::CheckTxAssets` in `src/consensus/tx_verify.cpp`. Java tests are
constructed positive/negative scenarios, not independent Core acceptance runs.

`RvnCanonicalAssetBlockChecks` combines native block accounting, lock/sigop
checks, signed canonical P2PKH asset spends, intra-block UTXO/metadata overlays,
root/sub issue/transfer/reissue authorization and one reissue per asset per block.
`RvnTaggedAssetIssueChecks` adds unique batches with a common immediate parent,
distinct names, no new owner creation and one exact burn output of 5 RVN per
issued token, plus single message-channel issuance with a 100 RVN burn and
messaging activation. Both require spent/returned parent ownership. The block
checker dispatches these families and can transfer them later in the same block;
unique/channel transfers require exactly one token. The explicit activation
overload of transfer checks is used by all transaction/block admission APIs.
Rules follow the pinned Core `VerifyNewUniqueAsset`, `VerifyNewMsgChannelAsset`
and `ContextualCheckTransferAsset` functions. Four tagged-transaction scenarios
and a signed issue/batch/transfer/channel block test cover positive and negative
cases; they do not constitute independent Core block acceptance fixtures.
It returns coin and metadata deltas suitable for a single atomic store commit.
The asset signature adapter executes the checked P2PKH prefix while retaining
the complete original asset scriptCode for signature hashing, matching Core's
suffix-consuming OP_RVN_ASSET semantics. Tests reject prefix-only signatures.
Caller supplies activation/enforced-value state from the validated branch.
This explicit canonical subset is **not full historical asset block consensus**
and is not enabled in native replay. Unsupported families/null data/envelopes
fail closed; qualifier/restricted verifier state, era-dependent permissive
parsing, complete deployment handling and accepted asset-era block fixtures
remain required before removing the replay gate or enabling mainnet mining.
