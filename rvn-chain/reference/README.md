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
The block checker below wires these APIs into opt-in replay: it resolves inputs,
checks native values and signatures, and commits metadata with UTXO/undo
atomically. Full historical acceptance still needs the remaining cases and
independent accepted-block fixtures described below.
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
The asset signature adapter executes the original script in the explicit
RAVENCOIN dialect. OP_RVN_ASSET consumes the entire suffix, including malformed
push bytes and CODESEPARATOR bytes, with Core's operand-size/opcount behavior.
Legacy hashing and FindAndDelete treat that suffix as opaque. P2SH and witness-v0
propagate the dialect; existing Bitcoin APIs retain their original default.
Tests cover all six legacy SIGHASH modes and reject prefix-only signatures.
Caller supplies activation/enforced-value state from the validated branch.
This explicit canonical subset is **not full historical asset block consensus**.
Default replay still stops at assets. Opt-in canonical replay now connects the
checker to synchronized bodies and commits coin/metadata/rule plans together.
Unsupported formats stop before tip commit; mining remains disabled.

Qualifier/subqualifier issuance checks exact mainnet burns (1000/100 RVN), no
owner creation, and spent/returned parent qualifier for a subqualifier. Restricted
issuance checks its 1500 RVN burn, root owner return and one verifier. Restricted
reissue normalizes `$TOKEN` authority to `TOKEN!`. Address tags, address/global
freezes and verifiers produce immutable rule deltas; tagging adds a single exact
0.1 RVN burn per added tag. Restricted transfers check the **parent snapshot**
for source freezes, global freezes, qualifiers and verifier, regardless of the
ordering of changes in the candidate. New issuance/reissue verifier updates have
their own destination checks. Rule/coin/metadata undo and tip share one batch.

`RvnHistoricalAssetCodec` implements Core's positional classification (without
requiring P2PKH/push length/OP_DROP), the pre-fix transfer offset, ignored trailing
bytes, optional hash length/marker/truncation semantics and fixed null-data
offsets. It preserves raw flags/hash bytes instead of silently normalizing invalid
data into a canonical payload. Decoding alone does not authorize a spend.
`RvnScriptSemantics` adds suffix-consuming sigop counting and Core pruning of
null/zero-asset outputs. Tests are constructed scenarios, not a Core execution
oracle or a corpus of accepted historical blocks.

Opt-in replay now uses the historical positional decoder for inputs and outputs,
including the branch-specific transfer offset and pre-RIP5 annotations. The
canonical constructor/checker entry points remain strict. A signed synthetic
block issues and spends a noncanonical envelope with an opaque CODESEPARATOR.

`generate-verifier-vectors.ps1` builds the original pinned Core LibBoolEE using
`verifier-reference.cpp` and MSVC. Its 280 committed cases cover 35 expressions
under eight qualifier assignments, including malformed leading characters.
`RvnLibBoolEvaluator` is adapted from Adam Streck's LGPL-3.0-or-later LibBoolEE;
notices are in the source and full LGPL/GPL texts ship in META-INF/licenses.
The oracle covers expression evaluation, not all asset-name or block rules.

Remaining hard limits: removing a root qualifier held
only through a subqualifier stops explicitly; pre-deployment asset coinbase
null-data and nonstandard metadata updates are still unsupported. Transfer,
ordinary issuance and reissue coinbase paths now have separate stateless checks.
Vote assets are outside this implementation. See [the detailed audit](HISTORICAL_ASSET_GAPS.md).
These limits must be resolved and replayed against independent accepted fixtures
before calling the pipeline full historical validation or enabling mining.


Additional v4.8.0 accounting audit: historical GetValueOut(false) ignores
IsAssetScript outputs when computing fees and coinbase reward. This is separate
from CheckTransaction's range checks and the UTXO's original native value.
The branch-dependent checker now propagates that flag throughout native checks.
Source: https://github.com/RavenProject/Ravencoin/blob/v4.8.0/src/primitives/transaction.cpp
Root/sub issuance also compares owner/issue key-hash destinations, as in
IsNewOwnerTxValid; it does not require identical historical locking prefixes.
