# Standalone RVN full node implementation status

The target is a locally validating and mining Ravencoin full node, independent
of a pool or Ravencoin Core RPC. Bitcoin's existing launcher, profiles, GPU
worker, data and configuration are not changed by this work.

## Implemented in this change

- Correct FNV-1 for dataset construction and FNV-1a for ProgPoW initialization
  and reduction. Earlier correction notes and tests had these reversed.
- Respect Core's floor(full_dataset_num_items / 2) for 256-byte DAG accesses.
  The prime number of 128-byte rows need not be divisible by two.
- A CPU light KawPoW engine: epoch cache, ProgPoW mix, domain-separated seed
  and final Keccak-f800 computation, and raw 120-byte header integration.
- Core's display/wire byte order conversion for header input, mix and result.
- Timestamp-based algorithm routing; declared height remains independently
  checked against the validated parent's height.
- Mainnet historical difficulty calculation: 2016-block retargeting, DGW from
  height 338778, and the temporary KawPoW transition target. An ExpectedBits
  adapter reads the candidate's own verified branch, including genesis.
- rvn-app now depends on rvn-pow and has an optional CPU known-answer check.
- A separate IntelliJ Spring Boot configuration:
  `RavencoinNodeApplication (development)`.
- Pure Java X16R and X16RV2, with all sixteen 512-bit primitives and Tiger192.
  The missing primitives are vendored unchanged from the pinned sphlib revision
  documented in `rvn-pow/SPHLIB_PROVENANCE.md`; no JNI or Core process is needed.
- The shared Java era engine is wired into rvn-app. The optional startup check
  now validates mainnet genesis, all three Tiger substitutions and KawPoW.
- Independent `rvn-storage` module: RocksDB header records, bounded LRU cache,
  skip links for ancestry/locators, fork chainwork selection, synchronous atomic
  batch + best-tip writes, genesis/schema identity and an exclusive DB lock.
  It refuses nonempty unmarked directories before opening RocksDB.
- Mainnet header admission connects actual PoW, expected difficulty, MTP,
  2-hour / 12-minute future-time bounds and branch-specific BIP9 assets activation
  for the header-version rule (including Raven's bit-only signalling condition).
  Full transaction/deployment/asset validation is still a separate pending stage.
- Opt-in supervised header synchronization with bounded requests, retries,
  peer rotation, persistent restart locators, side-branch pagination and shutdown.
  Mandatory hash self-tests run before opening a synchronization database.
- `rvn-chain` reuses the existing immutable transaction model/codecs without
  modifying Bitcoin code, with RVN-specific 80/120-byte block framing, native
  RVN money limits, transaction/coinbase shape checks, Merkle mutation detection
  and witness commitments. Pre-assets / RIP2 block weight limits are enforced.
- Witness `getdata` block requests and optional sequential body download. Bodies
  use a forced append-only HDD archive with an atomic SSD reference/metadata
  and download-cursor commit; header reorgs rewind this cursor while retaining
  cached off-branch bodies. These are candidate bodies, not validated UTXO state.
- RVN P2P accepts up to 8 MB per frame, with a 32 MiB inbound queue byte budget
  in addition to the existing message-count limit.
- `ravencoin.mining.address: ${RVN_MINING_ADDRESS:}` binds the requested receiving
  address. Base58Check checksum and network/type (RVN P2PKH/P2SH) are validated.
  The parsed payout object retains only the script, not the original address;
  neither successful startup nor validation errors print the address.
- Coinbase assembly supports the configured payout script, BIP34 height,
  extraNonce, RVN's 5000-coin subsidy / 2,100,000-block halvings and explicit fees.
- `RvnBlockTemplateBuilder` assembles KawPoW candidates from caller-supplied
  transactions and contextual header fields, adds witness commitment/reserved
  value when needed, computes the transaction Merkle root and applies body checks.
  Worker nonce/mix insertion preserves the 80-byte KawPoW input. This is a
  candidate assembly API, not a validated template service: UTXO/assets/fees,
  transaction dependency ordering, header context and worker proof must still
  be independently validated by the future mining lifecycle.
- Native RVN coin accounting (`RvnCoin`, `RvnCoinView`, `RvnCoinAccounting`)
  derives fees from a stable parent snapshot, enforces money ranges, coinbase
  maturity, ordered in-block dependencies, double-spend/output collision checks
  and the subsidy-plus-fees reward limit. Immutable net created/spent maps retain
  parent coins for future undo without mutating storage. Unspendable outputs are
  omitted. `RvnBlockTemplateBuilder.buildFromCoins` derives fees using this API;
  scripts, locks, assets and contextual rules still require separate validation.
- `RVN_DATA_DIRECTORY` selects the RVN data root. `RVN_MINING_ENABLED` binds the
  mining switch, defaulting to false; true still fails because the lifecycle is
  incomplete. Body download without header sync now fails startup explicitly.
- `RvnNativeScriptChecks` adapts shared legacy/P2SH and witness-v0 primitives
  without Bitcoin's Taproot/anchor shortcuts. It rejects unsupported/policy flags,
  noncanonical nested witness wrappers and unexpected witness data. The caller
  must supply RVN branch-specific activation flags; there is no default mainnet
  activation policy in this adapter. Asset opcode semantics remain unsupported.
- `RvnNativeTransactionChecks` combines native accounting with input scripts and
  absolute locktime, using an explicit caller-supplied time cutoff. Resolved coins
  include preceding in-block outputs. `buildWithNativeChecks` connects these
  checks to candidate template construction. The `RvnSpendContext` overload adds
  BIP68 height/time locks with a branch-specific ancestor MTP resolver, 512-second
  time units, unsigned transaction versions and in-block dependency handling.
  `RvnSigOpCost` enforces the 80000-unit block budget including coinbase, legacy,
  P2SH and witness costs. RIP2 does not double this sigop limit. The compatibility
  overload without branch context still omits BIP68. Assets and full contextual
  validation remain pending. These APIs are now wired to opt-in historical native
  replay; its persisted progress is not a full-block validation or mining status.
- `RvnCoinStore` adds a separate native coin RocksDB with an empty genesis coin
  set, synchronous atomic coin/undo-reference/tip commits, stale-parent checks, exact spent
  coin matching, creation collision checks and tip-only disconnect. Undo retains
  original coins and has a checksum. Reopening verifies identity/tip, current undo
  and affected coins. Foreign directories, missing CURRENT, wrong network and
  concurrent opens are rejected. Spring opens this store only in historical
  native replay mode. The asset-aware overload also commits immutable metadata
  before/after deltas in the same batch and shared undo, checks stale asset values,
  and verifies/restores affected metadata on reopen/disconnect. First asset writes
  atomically upgrade the DB schema so native-only binaries refuse to reopen it.
  Asset block validation must still supply these deltas before this becomes a
  production full-block chainstate.
- Tiered storage: `RvnFlatFileStore` writes block/undo payloads sequentially to
  128 MiB segments on K:, forces them, and only then allows synchronous RocksDB
  reference/state commits on C:. Crashes before index commit leave unreachable
  tails without moving the tip. Reads check bounded references, record headers
  and checksums. Archives have independent identities/locks. Legacy inline APIs
  remain available; Spring uses external payloads. Existing DBs are not silently
  relocated/reset, and indexed archives cannot be omitted on reopen.
- RocksDB caches are explicitly bounded: headers 64 MiB, coins 256 MiB, plus two
  32 MiB write buffers per DB. RVN IntelliJ profiles use a 4 GiB heap and 512 MiB
  Java direct-buffer limit (separate from RocksDB JNI memory). GPU admission
  planning checks one epoch DAG plus a device reserve against actual free VRAM;
  the separate CUDA worker performs its own allocation-time VRAM checks.
  Analysis: [STORAGE_AND_MEMORY.md](STORAGE_AND_MEMORY.md).
- `RvnNativeBlockReplay` processes stored bodies on the best verified header
  branch, checks exact BIP34 coinbase height, native scripts/amounts/locks/sigops
  with parent-branch MTP, and commits coins/undo. It rewinds during header reorgs
  and resumes after restart. Cached bodies are replayed before DNS/handshake.
  Default replay stops before assets activation; opt-in canonical subset replay
  checks supported asset blocks and commits metadata/rules with coins. Unsupported
  historical rules still stop it. Stored replay failures stop the sync worker
  rather than repeatedly blaming another peer. No mining lifecycle is enabled.
- Asset foundation: `RvnAssetNames` classifies root/sub/unique/channel/owner/vote/
  qualifier/restricted names against pinned Core grammar. `RvnAssetScriptCodec`
  represents canonical issuance, owner, transfer and reissue P2PKH envelopes,
  IPFS/transaction metadata hashes, optional transfer expiry and signed unit
  fields. This strict codec is not a full historical consensus classifier.
- `RvnAssetMetadataRules` checks canonical issuance/reissuance transitions:
  duplicate names, integer amount precision, unit bounds, special unique/channel/
  qualifier supply parameters, activation gates, cumulative supply and permanent
  disabling of reissuance. It retains metadata on a no-change reissue. These
  rules alone do not authorize transactions. Canonical asset block checks now
  include qualifier/restricted issuance and transfers, null rule authorization,
  tag burns, parent-snapshot freeze/verifier checks and atomic undo. Historical
  acceptance gaps remain; default replay still stops before asset activation.

## Verification and limits

Four Core hash vectors (heights 0, 49, 50, 30000) check both mix and final hash.
Additionally, 125 fixtures generated with C primitives from pinned Ravencoin
Core commit `6d48ae0175b10283248146ae3080e2ba70966739` check all sixteen primitives,
Tiger192, and both historical chains. The generator, chain wrapper and MSVC
build script are in `rvn-pow/native/reference`; the fixtures run offline in
JUnit. The independently published mainnet genesis hash is also checked.
Concurrent calls to a shared Java engine are tested for digest state isolation.
Storage tests include atomic rejection, duplicate batches, a heavier shorter
fork, 9000 headers exceeding the memory cache, skip links after restart,
interruption and refusal to open foreign/locked databases. Loopback P2P tests
cover persistent pagination, side-branch cursors and partial frames delayed
beyond the former socket timeout. Spring startup tests cover offline operation,
isolated sync storage, shutdown and reopening the DB.

Positive network/storage integration tests use explicitly test-only synthetic
proofs; a separate admission test with the real Java engine rejects a synthetic
invalid proof. Full mainnet synchronization has not been established: the four
public peers probed from this environment timed out on TCP port 8767.

New body tests reconstruct Core's full genesis transaction and published Merkle
root, cover malformed/truncated payloads, witness tampering, RIP2-sized bodies,
RVN amount limits, body cursor recovery/reorg and corrupt metadata. A local peer
integration test covers headers -> witness getdata -> bodies -> restart. Payout
tests cover checksum/network/script conversion, coinbase halvings/extraNonce and
Spring binding from the exact `RVN_MINING_ADDRESS` placeholder. Published Core
burn addresses are test vectors only and are never a default payout.

On 2026-10-24, `mvn -pl rvn-app -am test -B -q` passed before and after the
template addition. The available local C oracle executable also reproduced all
125 committed historical vectors; it was not rebuilt during this audit.
Four new template tests cover coinbase-only roundtrip, explicit fee payout,
witness commitment with an independently assembled two-leaf preimage, witness
tampering, extraNonce job changes, nonce64/mix insertion and rejected inputs.
The subsequent accounting/configuration change also passed the full
`mvn -pl rvn-app -am test -B -q` run. Seven accounting tests cover maturity at
99/100 blocks, native RVN amounts above Bitcoin's limit, aggregate input overflow,
overspending/double spending, dependency order, undo data, reward limits, output
collisions and unspendable-output pruning. Three Spring tests cover environment
binding and rejected startup combinations. These are synthetic accounting tests,
not evidence of accepted mainnet blocks or a persistent UTXO chainstate.
The native script adapter passed `mvn -pl rvn-app -am test -B -q` before the
final template API addition; `mvn -pl rvn-chain -am test -B -q` then checks that
addition. Seven script/transaction tests cover signed P2PKH/P2WPKH, altered
outputs and witness amounts, native/nested P2WSH, canonical wrappers, legacy
P2SH, unexpected witness, unknown witness versions, rejected flags, locktime
boundaries, in-block coin resolution and template script rejection. These use
synthetic transactions, not an independently exported RVN transaction corpus.
The BIP68/sigop addition passed `mvn -pl rvn-app -am test -B -q`. Five new tests
cover height/time boundaries, activation/disable flags, signed-version handling,
same-block zero/positive delays, missing ancestry, the 80000/80004-unit boundary
including coinbase, and P2SH/witness cost scaling.
Seven native coin-store tests cover restart, connect/disconnect, alternate-branch
reconnect, exact coin restoration, stale parents, rejected deltas, directory/DB
identity, lock exclusion, corrupt coins/undo and an actual child-JVM
`Runtime.halt(17)` after a synchronous commit without closing RocksDB. Reopening
that DB recovers the committed coins/tip and can undo them successfully.
Historical native replay passed the full `mvn -pl rvn-app -am test -B -q` run.
Six replay tests cover cached/missing bodies, restart, interrupted branch switch,
coinbase height/reward/finality rejection, the assets stop, interruption, coin/header
metadata mismatch and replay-before-network order. Two independent BIP34 prefix
tests cover small opcodes, sign padding and rejected nonminimal encodings. Spring
tests cover valid replay storage ownership/shutdown and invalid prerequisites
without opening either DB. Positive replay proofs remain explicitly synthetic.
The asset foundation adds 10 tests, including all 124 literal name-validity
assertions extracted from pinned Core `asset_tests.cpp`, fixed canonical format
examples, roundtrips with hashes/messages, truncation/malformed envelope checks,
and metadata supply/unit/activation/reissue boundaries. Fixtures and regeneration
scope are documented in `rvn-chain/reference/README.md`. Format examples are
synthetic; no accepted asset-era blocks have been replayed by this change.
The coin-store change passed `mvn -pl rvn-app -am test -B -q`; subsequent
reopen-integrity/ancestor-ID checks passed `mvn -pl rvn-storage -am test -B -q`.

Local launch audit: NVIDIA reports an RTX 4070 with 12282 MiB VRAM. The repository
original GPU worker implements Bitcoin SHA256d; RVN now has a separate CUDA
KawPoW worker and hardware self-test. Ravencoin Core binaries are
installed under `C:/Program Files/Raven`, but no Raven process was running during
the audit. No RVN mainnet mining process has been started. The user explicitly
confirmed the target: an independent RVN node and GPU mining on that node.
Pool/Core RPC fallbacks are excluded from this implementation. CUDA Toolkit
12.2 is available locally and NVRTC is used by the new KawPoW GPU worker.
The packaged JAR passed offline startup with both historical and KawPoW
self-tests. An actual P2P probe of `seed-raven.ravencoin.com` failed with
`UnknownHostException` in this environment; no mainnet handshake was established.

The synthetic chain vectors are not a replay of accepted historical blocks;
real X16RV2 block fixtures and full historical chain replay remain required.
Raw header tests cover endian conversion, accepting the computed mix and
rejecting a modified mix. Difficulty tests cover historical interval edges,
DGW activation, time clamps and the KawPoW transition; these are synthetic
regressions, not yet a replay of independently exported historical Core data.

The CPU engine is suitable for further verifier integration and testing, not
an efficient GPU miner. Matching these vectors does not certify the complete
consensus implementation.

Run with JDK 21 and Maven, without deleting Bitcoin's target/log directories:

```powershell
mvn -pl rvn-app -am package
java -Xmx512m -jar rvn-app/target/rvn-app-0.0.1-SNAPSHOT.jar --ravencoin.pow.self-test-enabled=true
```

The development launcher checks the engine and exits in the default offline
mode. Choose `RavencoinNodeApplication (mainnet headers)` in IntelliJ to start
header synchronization, or use:

```powershell
java -Xmx2g -jar rvn-app/target/rvn-app-0.0.1-SNAPSHOT.jar --ravencoin.sync.enabled=true
```

The cold root is `K:/RVNJavaNjde` (`RVN_DATA_DIRECTORY`); the hot NVMe root is
`C:/RVNJavaNjde` (`RVN_HOT_DIRECTORY` / `ravencoin.storage.hot-directory`).
`RvnDataDirectories` resolves headers/coins/tmp under C: and blocks/undo/archives/
logs under K:. Optional disk DAG snapshots are reserved under K:/.../dag; the
current CPU light cache stays in RAM. JVM temporary files use C:/.../tmp in RVN
IntelliJ profiles; crash logs and heap dumps use K:/.../logs and /archives.
Application logs retain 20 MB rotation, 14-day history and a 512 MB archive cap.
If overriding either root, update IntelliJ VM paths too. Command-line PowerShell
can use `'-Djava.io.tmpdir=C:/RVNJavaNjde/tmp'` before `-jar`; create tmp first.
Build/test artifacts remain in Maven target directories. No existing DB is moved
or imported automatically. The new layout refuses to ignore a legacy cold DB.
The full tiered change passed `mvn -pl rvn-app -am package -B -q`. Five storage
tests cover rotation, identities, references instead of large DB payloads,
corruption/missing files and actual child-JVM halts before/after index commit.
Two GPU memory-plan tests cover epoch boundaries, free VRAM and reserve gates.
An isolated real C:/K: smoke check passed payload/state commit, reopen and undo;
neither this smoke nor crash tests simulate physical power loss or benchmark IBD.

`RVN_SYNC_PEERS` is a comma-separated list of hostnames/IPs; `ravencoin.p2p.port`
overrides their common port. Only mainnet header synchronization is supported.
Probe/session diagnostic modes cannot be combined with synchronization.
The persisted local database is trusted local state, like other node databases;
an externally supplied DB is not authenticated and requires full revalidation.
No Bitcoin data is imported or reused. Setting `ravencoin.mining.enabled=true`
still fails deliberately. Header height is never advertised as a validated
full-block/UTXO height.

For commitment-checked body download choose IntelliJ configuration
`RavencoinNodeApplication (mainnet block download)` or additionally set
`--ravencoin.sync.blocks-enabled=true` / `RVN_SYNC_BLOCKS=true`. This mode also
keeps mining disabled. Bodies now live in K:/RVNJavaNjde/blocks segments; only
their references and integrity metadata live alongside headers on C:.

For historical native replay use IntelliJ configuration
`RavencoinNodeApplication (historical native replay)`, or enable all of
`RVN_SYNC_ENABLED=true`, `RVN_SYNC_BLOCKS=true` and `RVN_NATIVE_REPLAY=true`.
The last switch maps to `ravencoin.validation.native-replay-enabled`, default false.
It requires both header sync and body download. Progress resumes from the native
coin tip under `C:/RVNJavaNjde/coins`, independently of the body-download cursor.
Processing stops at assets activation because asset validation/state is pending.
Neither this native tip nor the downloaded-body cursor makes the node mining-ready.

Set `RVN_MINING_ADDRESS` in the environment inherited by the RVN JVM (including
IntelliJ's selected RVN run configuration). No address is hardcoded in production
or copied into shared run configurations. If a Windows user environment variable
was added after IntelliJ started, restart the IDE so its child JVM inherits it.
Only the configured/not-configured status is logged, never the receiving value.
Payout configuration does not enable mining before full validation is implemented.

## Work still required for the requested full node

1. Validate X16R/X16RV2 against additional accepted historical block vectors,
   especially X16RV2 activation, and replay historical difficulty data.
2. Exercise real mainnet header synchronization/recovery and historical replay;
   broaden peer discovery/management and verify real mainnet body download. The new
   persistent header path replaces the bounded in-memory graph for sync mode.
3. Integrate native checks and durable UTXO/undo with full contextual block
   admission, RVN assets/deployment state and coordinated recovery/reorganizations.
   Historical native replay is connected to durable coin storage, but is not a
   validated full-block chainstate. Asset mutations must be committed consistently
   with native coins and asset-era blocks must not pass through the native-only path.
4. Mempool, fully validated fee accounting, a template service tied to validated chain
   state, candidate submission and relay. Candidate/coinbase/witness assembly is
   implemented; fees can be derived from a caller-supplied stable coin snapshot.
   Context and complete transaction validity must still be established.
5. Connect the CUDA KawPoW worker to validated jobs, cancellation on tip changes, local candidate
   validation and network submission.
6. End-to-end tests with independently accepted blocks, then enable the
   production mining lifecycle in the dedicated Spring Boot launcher.

Canonical asset transaction checks now include ordinary root/subasset transfer
conservation (including owner tokens), metadata/units checks and bounded sums.
Mainnet root/sub issuance plans validate exact burn outputs, the final owner/issue
pair, and spent/returned parent ownership for subassets. Reissue plans check the
exact 100 RVN mainnet burn, owner input/return, metadata transitions and conservation
of existing transfer balances. Eight transaction scenarios cover these rules.
Seven asset/coin storage tests cover restart, reorg, stale-plan rejection,
registry tampering, schema upgrade and abrupt child-process termination with
external shared undo. These are synthetic fixtures, not accepted mainnet blocks.
Complete historical script/qualifier/verifier compatibility still blocks full
asset-era replay and production mining. A canonical mainnet asset block validator combines
native checks, full-script asset signatures, intra-block coin/metadata overlays
and net atomic plans; it rejects repeated reissues and unsupported script forms.
Ten signed/synthetic block tests exercise this subset. Unique issuance supports
multi-token batches with a shared immediate parent, distinct names, exact 5 RVN
burn per token and spent/returned parent ownership. Channels require an exact
100 RVN burn, one issuance and messaging activation. Both families transfer as
one indivisible token. Four additional tagged-transaction tests check these
rules, duplicate/existing names, mixed parents, burns and activation. Full historical asset
consensus is not claimed. Default replay stops before activation; explicit
`ravencoin.validation.canonical-asset-replay-enabled=true` now connects the
supported checker to synchronized bodies and atomic coin/metadata/rule commits.
It requires native replay, sync and bodies. Branch-derived RIP5/transfer-size/
value/coinbase deployment state is available (value enforcement includes LOCKED_IN
as in Core). Unsupported historical formats fail before tip commit. A synthetic
replay/restart/reorg test verifies that asset admission and undo share the coin tip.

Historical positional/null decoding is implemented separately, preserving ignored
tails, pre-fix transfer offsets, raw flags and hash truncation. RVN-specific sigop
counting consumes the asset suffix, and null/zero-asset outputs are pruned. This
decoder is not yet a historical signature interpreter. Remaining blockers are
arbitrary historical asset spends, LibBoolEE malformed-expression compatibility,
removing inherited-only root qualifiers and pre-deployment asset coinbase cases,
plus independent accepted-block replay. See [asset reference scope](../rvn-chain/reference/README.md).

CUDA KawPoW now has a real local worker: GPU DAG generation, height/3 generated
programs, bounded uint64 nonce search and full 256-bit target comparison. The
Java bridge recomputes every candidate with the CPU light verifier and enforces
timeouts/ranges/result bounds. RTX 4070 hardware tests passed all four Core
vectors, epoch replacement and unsigned nonce/target boundaries. A Spring
opt-in CUDA diagnostic and IntelliJ CUDA Check profile are available; neither
supplies production mining jobs. Details: [CUDA worker](../rvn-pow/native/cuda/README.md).

## Reference sources

- https://github.com/RavenProject/Ravencoin/blob/6d48ae0175b10283248146ae3080e2ba70966739/src/assets/assets.cpp
- https://github.com/RavenProject/Ravencoin/blob/6d48ae0175b10283248146ae3080e2ba70966739/src/assets/assettypes.h
- https://github.com/RavenProject/Ravencoin/blob/6d48ae0175b10283248146ae3080e2ba70966739/src/test/assets/asset_tests.cpp
- https://github.com/RavenProject/Ravencoin/blob/master/src/consensus/consensus.h
- https://github.com/RavenProject/Ravencoin/blob/master/src/consensus/tx_verify.cpp
- https://github.com/RavenProject/Ravencoin/blob/master/src/script/interpreter.cpp
- https://github.com/RavenProject/Ravencoin/blob/master/src/crypto/ethash/lib/ethash/bit_manipulation.h
- https://github.com/RavenProject/Ravencoin/blob/master/src/crypto/ethash/lib/ethash/progpow.cpp
- https://github.com/RavenProject/Ravencoin/blob/master/src/crypto/ethash/progpow_test_vectors.hpp
- https://github.com/RavenProject/Ravencoin/blob/master/src/hash.cpp
- https://github.com/RavenProject/Ravencoin/blob/master/src/primitives/block.cpp
- https://github.com/RavenProject/Ravencoin/blob/master/src/pow.cpp
- https://github.com/RavenProject/Ravencoin/blob/master/src/chainparams.cpp

These references describe the source inspected during this change. A pinned
Core release and full external fixture corpus remain required for consensus
release validation.
