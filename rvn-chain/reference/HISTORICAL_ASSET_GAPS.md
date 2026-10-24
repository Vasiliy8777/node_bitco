# Historical assets: remaining certification gaps

Audited against Ravencoin Core v4.8.0. This file is a list of unresolved
consensus differences, not a claim that the Java validator is complete.

## Root qualifier removal inherited only from a child

The exact persisted assignment and effective root membership are different.
For address A with only `#KYC/#ID`, `hasQualifier(#KYC,A)` is true while the
exact `(#KYC,A)` assignment is absent. Core permits a root REMOVE against this
aggregate parent membership.

The command cannot safely be represented as deleting children or as an
unconditionally ignored exact-key deletion:

* `CheckForAddressQualifier` consults the global command cache before the DB.
  A cached root REMOVE can return false, unless the root-child add cache is
  nonempty. `fSkipTempCache=true` still consults the global cache.
* `DumpCacheToDatabase` erases only the exact root key; persisted children
  remain. The DB prefix lookup then reports inherited root membership again.
* `Flush` merges both root-child Add and Remove sets into the global Add map
  in this release. This is the actual source behavior, not an assumed fix.
* Undoing a REMOVE writes the exact root assignment, even when the command
  was authorized by inherited-only membership. Java's exact-before undo model
  must not silently pretend this is the same transition.

The current checker stops explicitly on inherited-only removal. Resolve the
cache/flush/undo compatibility with independent accepted block fixtures before
removing that stop or allowing production mining. No environment flag overrides it.

Sources:
[assets.cpp](https://github.com/RavenProject/Ravencoin/blob/v4.8.0/src/assets/assets.cpp)
(`AddQualifierAddress`, `RemoveQualifierAddress`, `Flush`, `DumpCacheToDatabase`,
`CheckForAddressQualifier`),
[restricteddb.cpp](https://github.com/RavenProject/Ravencoin/blob/v4.8.0/src/assets/restricteddb.cpp)
(`CheckForAddressRootQualifier`).

## Historical coinbase

Implemented: bit-10 branch activation rejects asset/null coinbase outputs;
before activation, transfers use stateless type/amount/native-value rules,
without conservation, metadata existence, units, destination or freeze checks.
Root/sub/unique/channel/qualifier issuance and ordinary reissue use stateless
layout/burn/owner checks. Duplicate issuance leaves old metadata intact; a
reissue without existing metadata creates no metadata update, matching the
nonfatal AddCoins cache calls. Normal non-coinbase checks remain contextual.

Still unsupported: coinbase null-data/rule changes and restricted issuance;
coinbase reissue producing metadata outside the current supply/granularity
representation; asset-bearing coinbase before initial assets activation.
These cases stop before committing the coin tip. Vote assets remain unsupported.

Sources:
[tx_verify.cpp](https://github.com/RavenProject/Ravencoin/blob/v4.8.0/src/consensus/tx_verify.cpp),
[coins.cpp](https://github.com/RavenProject/Ravencoin/blob/v4.8.0/src/coins.cpp),
[validation.cpp](https://github.com/RavenProject/Ravencoin/blob/v4.8.0/src/validation.cpp).

## Transfer overflow deployment

Bit 11 uses the mainnet 2016-block window, threshold 1411,
start 1781222401, timeout 1812844799, and enforces only ACTIVE.
Before activation, transfers remain positive, but signed input/output totals
wrap in 64 bits. After activation, individual quantities and each running sum
must be within MoneyRange. The activation travels through all issuance/reissue
transfer checks as well as transfer-only transactions. No sticky global state
survives a branch change.

Tests cover wrapping, total bounds, bit independence, LOCKED_IN versus ACTIVE,
and an alternate unsignalling branch. Tests are constructed vectors; a complete
independent accepted historical block corpus is still required.

Sources:
[chainparams.cpp](https://github.com/RavenProject/Ravencoin/blob/v4.8.0/src/chainparams.cpp),
[tx_verify.cpp](https://github.com/RavenProject/Ravencoin/blob/v4.8.0/src/consensus/tx_verify.cpp).
