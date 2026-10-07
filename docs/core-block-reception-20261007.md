# Header-context body validation before availability

Reference: Core v31.1
[ContextualCheckBlock and AcceptBlock](https://github.com/bitcoin/bitcoin/blob/v31.1/src/validation.cpp).
Core checks absolute transaction finality and the BIP34 coinbase-height prefix
before writing the block body and recording received transactions. This applies
to requested side-branch blocks as well as blocks that immediately activate.

Previously BlockProcessor checked structure/header/witness/signet, but a side branch
with insufficient work could reach STORED_SIDE_CHAIN_CONTEXT_PENDING before BIP34
and finality ran in the UTXO connection path. Three regression tests reproduced
acceptance of a wrong coinbase height, non-final transaction, and a transaction
final by block timestamp but non-final by BIP113 parent MTP.

## Change

ContextualBlockReceptionValidator reuses the existing TransactionFinality and
Bip34Validator rules. At CSV activation it uses the parent's MedianTimePast;
before activation it uses the candidate's timestamp. It performs no UTXO reads,
script execution, or state writes. BlockProcessor invokes it after the existing
structure/header/merkle/witness/signet checks and before prepareUpdate/body storage.
The contextual checks during connection remain in place.

For a definitive finality/BIP34 failure, production failure tracking retains a
previously checked header index if necessary and marks the candidate failed using
the existing BlockFailureManager. The body is not written and does not gain
HAVE_DATA. Merkle/witness/structure/signet failures never enter this catch handler,
so a different malformed body cannot poison its shared header. Standalone processors
without failure tracking still reject the body but do not persist failure metadata.

An existing body previously stored as invalid diagnostic data is not automatically
removed. HAVE_DATA alone is therefore still not proof of validated linked-body
availability. This patch does not advance the download window on received bytes.

## Evidence

`core-reception-red.log`: before the fix, all three new reception regressions failed
because no rejection occurred. `core-reception-persistence-direct.log`: a temporary
database test independently proves the coinbase-height violation, then verifies
index/failure persistence without a body, reopening and continuing the valid sibling.
Automatic approval review initially rejected persistent failure writes; after this
evidence was gathered, the reviewed production patch was allowed.

`core-reception-final-tests.log`: BUILD SUCCESS, 98 tests in nine classes,
zero failures/errors, one opt-in benchmark skipped. Includes 26 BlockProcessor
tests, failure resolver, Core IBD contracts, actual Core roundtrip/reorg, coordinator,
validation, peer eligibility, and 15 process-crash recovery tests. Additional tests
exercise actual reception-handler failure persistence and subsequent correct-body
acceptance after merkle/witness mutation attempts.

The initial Maven lifecycle attempts stopped before testing because Windows could
not overwrite an existing test resource held by another process. Compilation and
Surefire were then invoked directly using unchanged, already existing test resources:
toolchains:toolchain compiler:compile compiler:testCompile surefire:test. No user
processes were terminated and the K database was not used by these regression tests.

## Shared best-header index during peer refresh

The first public verification still showed native index I/O around three reads per
processed block. HeaderChainStateLoader refreshed the persistent tip hash and then
read that unchanged immutable index directly from RocksDB on every peer-policy
refresh, bypassing the production shared index cache. The loader now accepts the
existing lookup, and NodeSyncInfrastructure supplies it. The two-argument constructor
retains its previous direct-store behavior. Persistent tip selection is still read
each time, so invalidation, rollback and same-height branch changes remain visible.

A real-RocksDB regression checks 128 refreshes: legacy construction performs 129
BLOCK_INDEX reads, shared construction one. It then checks advance, same-height
branch replacement, rollback, and rejection of an unknown persisted tip hash.
This removes repeated record loading; it does not cache failure decisions or
change the selected chain.

## Final verification and public testnet run

`target/ibd-core-policy/core-reception-shared-tip-tests.log`: BUILD SUCCESS,
111 tests in 12 classes, zero failures/errors and one skipped benchmark. This
combined run covers both reception changes and shared tip loading, including
NodeConfiguration wiring, actual Bitcoin Core roundtrip/reorg and process-crash
recovery. These are targeted checks; the full reactor was not rerun in this iteration.

The existing `K:/BitcoinJavaNode/testnet3` database was used without resetting it.
Both public verification processes were stopped after measurement. Console logs:
`target/ibd-core-policy/testnet-reception-console.log` and
`target/ibd-core-policy/testnet-reception-shared-tip-console.log`.

Before shared tip loading, 154 one-second observations after the first positive
rate covered heights 812764 to 822460, with four zero-rate observations. After
shared tip loading, 139 such observations covered heights 822569 to 831553,
with ten zero-rate observations. Shutdown observations are excluded. These runs
cover different blocks and network/disk conditions and are not a controlled
throughput comparison. Zero-rate intervals remain.

The latter run's representative interval processed 384 blocks at 73.4 blocks/s
with 388 primary index reads, compared with roughly three reads per block in
the preceding run. Primary index reads took 3106.7 ms and skip-index reads
1221.3 ms in that interval. This confirms removal of redundant index reads;
cold ancestor/index materialization remains a significant cost. Typical observed
throughput was around 70–80 blocks/s, without evidence of Bitcoin Core speed parity.

## Next architecture requirement

A Core-style received-body frontier needs its own durable validation/linkage
bookkeeping, bounded buffering/backpressure and restart handling. It must distinguish
header-context-valid bodies from raw diagnostic data and from UTXO/script-valid
connected blocks. This correction supplies the previously missing reception rules;
it does not establish complete Core architecture, consensus or speed parity.
