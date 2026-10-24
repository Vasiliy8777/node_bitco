# Versioned historical replay and mining readiness

The validator version is `rvn-core-4.8.0-flushed-v1`. This identifies the exact
parent-snapshot policy as well as the transaction rules; it is not an operator
assertion that an imported database is valid.

## Qualifier snapshot and undo policy

Each block's coins, exact qualifier assignments, restricted rules, metadata,
undo, tip and validation coverage commit in one synchronous RocksDB batch.
A bounded RAM cache stores parent membership lookups; every successful connect
or disconnect clears it. A block-local command never changes another
transaction's parent-snapshot membership check.

Root membership includes persisted children. Removing an inherited-only root
is a valid command with no exact-key mutation: children are retained, no root
assignment is invented, and rollback restores the verified parent snapshot.
This is the durable state of Core after flushing that forward command.

The native oracle in `qualifier-reference.cpp` executes unchanged v4.8.0
`AddQualifierAddress`, `RemoveQualifierAddress`, `CheckForAddressQualifier` and
the qualifier portion of `DumpCacheToDatabase`, with test-only DB/cache
adapters. Regenerate with `generate-qualifier-vectors.ps1` (MSVC required).
Committed vectors include both dirty and flushed observations and the Core
inverse-undo side effect. The source SHA256 is recorded in the fixture.

**Compatibility boundary:** dirty Core caches can disagree with flushed state;
Core inverse undo can create an exact root assignment that did not exist
before the command. Java deliberately restores the verified branch snapshot,
equivalent to forward replay of that branch with per-block flush boundaries.
It does not claim byte-for-byte emulation of every Core cache/reorg history.
The oracle tests expose this difference rather than silently normalizing it.
An unsupported or invalid historical block stops replay and cannot enable GPU
work. Actual genesis-to-current-tip replay is still required on mainnet.

## Historical coinbase and metadata

Before bit 10, coinbase uses stateless layout/burn/owner/transfer checks, null
flag/duplicate/authorization/tag-burn checks and verifier syntax checks.
It skips contextual conservation, metadata/qualifier existence, destination
verification, freezes and redundant-status rejection. Restricted issuance and
verifier updates use this same distinction. Missing reissue predecessors do
not create metadata; duplicate issuance does not overwrite existing metadata.
Opaque global command names preserve their original byte identity; ordinary
address-null families ignored by AddCoins do not create restriction state.

Before initial asset activation the same stateless coinbase checks run, but no
asset registry/rule updates are installed. Unclassified leading OP_RVN_ASSET
scripts remain unspendable opaque outputs. Misplaced opcodes fail closed.
Bit 10 forbids recognized asset/null coinbase outputs once active.

Metadata is a state carrier, not issuance authorization. Signed 64-bit supply
and units 0..8 round-trip through RocksDB and undo, including negative/wrapped
supply and values not divisible by their units. New issue/reissue admission
still enforces the applicable rules. Reissue supply arithmetic mirrors the
signed addition in Core. Asset schema 4 prevents older binaries from opening
the new state; schemas 1/2/3 remain readable for revalidation.

## Coverage and startup

Coverage includes a version and an exact block id/height. It can be anchored
only at an empty genesis state, then extended atomically by validated block
commits. Generic storage commits invalidate it. Undo moves coverage back in
the same batch; a wrong-version/imported tip cannot become covered by merely
opening the DB or enabling a property.

Uncovered/wrong-version state is rewound through durable undo in bounded
batches, then replayed from saved bodies. Headers and block archives are not
deleted. An interrupted rewind/replay resumes after restart. This intentionally
rechecks data admitted by older validator versions.

`Start-RvnSync.ps1 -Mining` starts CUDA vector and local-job self-tests, then
arms the local solo service. GPU production search requires matching recent
header/coin tips, current-version contiguous genesis coverage, the mainnet
recovery checkpoint, and a responsive peer. ApplicationReadyEvent prevents
self-test/search races. Reorg, peer loss or validation failure invalidates work.
Initial synchronization is not a mining success; no reward is implied by the
local self-test or by a relayed block being returned by one peer.

Vote issuance remains outside this validator's supported layouts. Encountering
such an unsupported case stops replay; coverage never skips a failed block.

References: [Core assets.cpp](https://github.com/RavenProject/Ravencoin/blob/v4.8.0/src/assets/assets.cpp),
[tx_verify.cpp](https://github.com/RavenProject/Ravencoin/blob/v4.8.0/src/consensus/tx_verify.cpp),
[coins.cpp](https://github.com/RavenProject/Ravencoin/blob/v4.8.0/src/coins.cpp).
