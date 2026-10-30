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
decoder is now used by opt-in replay with branch-specific transfer offsets.
Original scripts execute under an explicit RVN dialect, including opaque asset
tails in legacy signature hashing, P2SH and witness-v0. Six SIGHASH modes are
covered with real signatures; Bitcoin defaults remain unchanged. Verifier
expression evaluation matches 280 cases generated by compiled pinned LibBoolEE,
including malformed leading characters (see reference generator and licenses).
Remaining blockers are removing inherited-only root qualifiers and
pre-deployment asset coinbase cases,
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


## Solo job pipeline (not enabled for mainnet)

`RvnSoloMining` connects bounded CUDA searches to immutable jobs, byte-order
conversion, nonce accounting, overflow subrange retries, stale/expired work
checks, candidate acceptance and relay retries. `RvnEmptyBlockMiningBackend`
assembles fee-free empty blocks from a synchronized coin/header parent, derives
branch difficulty, varies coinbase extraNonce and independently admits PoW,
body and coins before relay. It requires an explicit full-validation readiness
policy; it is deliberately not a production Spring bean while historical
consensus cases remain unsupported. Header/coin tip equality alone cannot
supply that policy. Mined candidates now have a single durable outbound slot committed with
coins/undo/tip. The backend recovers it after restart and clears it after a peer
returns the exact block via getdata. Reorgs remove disconnected candidates.
Production startup wiring and full historical validation remain incomplete.

`RVN_CUDA_JOB_SELF_TEST=true` together with `RVN_CUDA_ENABLED=true` and the
configured `RVN_MINING_ADDRESS` exercises an actual locally assembled GPU job
with a synthetic easy target, independent CPU proof and native block checks.
It uses memory-only admission and sends nothing to peers. It is not mainnet
mining and cannot earn a reward. The live mining startup guard stays in place.


Hardware/launch check on 2026-10-24: the packaged application passed all four
CUDA Core vectors and the local block-job self-test on RTX 4070, using the
configured payout. Native/CPU validation accepted the synthetic job. Six mining
pipeline tests cover durable connection/reopen, stale/expired work, retry after
relay failure, nonce refresh, overflow subranges and header/mix byte order.

The production-directory node was started with header/body sync and opt-in
subset replay, mining/CUDA disabled. System DNS returned malformed-packet
errors for the configured seeds. HTTPS resolution of official seed names
returned addresses, but TCP connections to 24 sampled addresses on port 8767
failed. No mainnet synchronization progress or mining readiness was established.
This network check does not prove that all peers are unavailable or identify
which network device is responsible. Resolve connectivity before claiming IBD
has started; historical consensus and production relay integration also remain.


## Synchronization recovery, 2026-10-24 (17:38 local)

The earlier connectivity failure was resolved using reachable bootstrap peers.
The node accepted real mainnet headers and replayed native blocks from its
existing C:/RVNJavaNjde and K:/RVNJavaNjde stores. Observed progress exceeded
260,000 headers and 1,500 fully replayed native-era blocks; these are distinct
heights and do not establish a mining-ready tip.

System DNS is bounded and has configurable HTTPS fallback. The full Jackson
parser replaces the limited fallback parser, preserves all answer records and
validates question/status/address data. Resolver closure rejects further calls;
invalid endpoint configuration no longer allocates HTTP/DNS resources first.

Block downloading now pipelines at most four outstanding requests with
individual deadlines. A batch contains up to 128 bodies, received in arbitrary
order and checked against their requested, validated headers. Bodies are saved
immediately; the contiguous download cursor advances through cached reordered
responses. Tests cover reordered responses, unknown headers/notfound, byte
limits, malformed DNS, cancellation and restart. The complete Maven package
build passed; the running node uses this updated artifact.

`tools/rvn/Start-RvnSync.ps1` starts a hidden background node and records its
process identity, paths and mode in an ignored target file. A repeated launch
was verified to reject the still-running process. No payout value is written
to the process metadata. See tools/rvn/README.md for launch and progress commands.

The v4.8.0 recovery checkpoint at 4,487,775 is enforced at admission and on
opening a tip beyond it. This does not certify the complete v4.8.0 asset rules:
historical overflow/deployment compatibility, qualifier/coinbase cases and
production candidate relay still require completion. Mining remains disabled.


## Historical value accounting and durable candidate propagation (2026-10-24)

Historical native accounting now follows Core v4.8.0 CTransaction::GetValueOut:
before enforced-values deployment, positional asset outputs are excluded from
fees/reward totals while their original native values remain in UTXO. Raw
output ranges are still checked independently. The branch's enforced-values
state reaches both accounting and script/lock validation. Tests cover ignored
noncanonical envelopes, retained coins, overpaying coinbase, negative values,
and a signed historical reissue admitted before deployment but rejected after.
Root/sub issuance additionally checks that the owner and asset destinations
match Core ExtractDestination, including historical noncanonical prefixes.

RvnCoinStore stores the mined-block outbox marker in the same durable RocksDB
batch as coins/assets/undo/tip. It cannot overwrite an unacknowledged slot.
A disconnect clears its own candidate atomically; descendants preserve it.
RvnNativeBlockReplay.connectMined uses the existing replay validator before
committing this marker. The empty-block backend recovers pending bodies before
issuing another job and never holds chain/store locks during network I/O.

The P2P owner thread sends the block and requests it back from the peer. The
outbox is acknowledged only after an identical full body returns, not merely
when bytes enter a local socket. A notfound, disconnect, cancellation or timeout
retains the durable candidate for retry. Peer possession does not establish
network-wide acceptance or a block reward. Tests cover positive/negative peer
confirmation, failed relay, durable reopen, stale acknowledgements and reorgs.

The Maven RVN reactor test suite passed and the packaged node was restarted on
its existing stores. This does not enable mainnet mining: remaining historical
asset/qualifier/coinbase/overflow cases and the production readiness/startup
connection are still explicit blockers. No full-validation certificate or
mining enable flag was fabricated from header/coin height equality.

## 2026-10-24: deployment accounting and guarded mining lifecycle

Core v4.8.0 bit 11 is derived from each parent branch (2016/1411,
1781222401..1812844799, ACTIVE only). Historical transfer totals wrap in
64 bits; deployed checks bound each input/output and running total. Every
issuance/reissue helper receives this activation through its transfer legs.
Owner transfers do not require base metadata; channel transfers are not
artificially restricted to one token. Non-coinbase assets before activation
are rejected before native accounting can advance the coin tip.

Bit 10 now reaches historical replay. It forbids asset/null coinbase outputs
when active. Earlier transfers skip contextual conservation/metadata checks;
root/sub/tagged/qualifier issuance and reissue have separate stateless
layout/burn/owner checks. Duplicate coinbase issuance preserves existing
metadata and a missing reissue predecessor does not fabricate metadata.

RvnMiningConfiguration now wires the real worker/backend/service. The service
waits for ApplicationReadyEvent, after CUDA ApplicationRunner self-tests, and
stops before synchronization/stores. A failed validator/worker terminates it
and releases GPU resources. Backend readiness additionally requires current
matching header/coin tips, recent time and a responsive synchronization peer;
old jobs are rechecked before and after search and before durable admission.

IMPORTANT: replay.completeHistoricalValidation() intentionally still returns
false. Enabling mining starts a waiting service, not GPU production work.
There is no operator override. The inherited-only qualifier removal/cache/undo
case, coinbase null/restricted updates, nonstandard legacy metadata and
pre-asset-activation coinbases still prevent historical certification.
See rvn-chain/reference/HISTORICAL_ASSET_GAPS.md for source-backed details.

The complete RVN Maven reactor tests passed, including deployment branch
switches, legacy overflow, stateless coinbase paths, startup prerequisites,
readiness/peer shutdown and GPU-idle behavior before validation readiness.
The artifact was packaged and synchronization restarted on existing C/K
stores with mining disabled. No data reset or mainnet mining claim was made.

## 2026-10-24: historical state profile, coverage and armed GPU service

Historical coinbase null/restricted checks and state updates are now connected,
including pre-asset-activation stateless checking without installing an asset
registry. Authorization transfers, burn fees, duplicate/flag checks and
verifier syntax are enforced; contextual qualifier/existence/freeze checks
are skipped only for historical coinbase. Opaque global names preserve their
Latin-1 byte identity. Bit 10 still rejects asset/null coinbase outputs.

Metadata now preserves signed/wrapped supply and non-divisible units as state,
without weakening new issuance rules. Schema 4 prevents old binaries opening
new asset state; old schemas remain readable for replay. Round-trip, restart,
undo and abrupt-halt tests cover atomic coverage/coins/assets/mined outbox.

Inherited-only root REMOVE is accepted as an exact-key no-op without deleting
children. A 4096-entry parent membership cache is cleared on commit/undo.
The native MSVC oracle executes unchanged Core qualifier methods and commits
six dirty/flushed/inverse observations. IMPORTANT: the selected Java profile
is per-block-flushed forward state with verified-snapshot rollback, not full
emulation of Core's cache-sensitive inverse-undo side effects. The difference
is exposed in the fixtures and HISTORICAL_VALIDATION.md, not silently hidden.

The old constant-false readiness gate is replaced by versioned coverage bound
to the exact tip. Only an empty genesis state can anchor it; validated commits
extend it atomically and generic commits invalidate it. An unversioned/wrong
version tip rewinds via durable undo and replays stored bodies. Restart during
rewind is tested. Native-only or test-deployment profiles cannot arm mainnet
work. Full mainnet history must actually replay without a failed block, pass
the recovery checkpoint and reach the recent matching best header/coin tip
with a responsive peer before GPU production search is allowed.

RVN reactor tests passed: rvn-chain 90, rvn-storage 31, rvn-app 44 (plus reactor
dependencies). Package build passed. Start-RvnSync.ps1 -Mining was executed
on the existing C/K stores. Launcher PID 85056, actual JVM 99216, Python CUDA
worker 110068 at launch. Logs 2026-10-24 19:17:55 +07 confirmed real RTX 4070
Core-vector tests and the local GPU job/independent CPU/native validation test.
At 19:17:56 the solo service entered WAITING. Old headers (height 652000) and
block archives were retained; replay rolled chainstate through undo to genesis
and began versioned revalidation of the saved bodies. This is not active
mainnet hashing or a mining reward. Initial sync remains in progress and the
guarded service stays running for automatic readiness checks.
# 2026-10-24: body-first initial sync and mining diagnostics

Initial download was growing the header/body gap: each 2000-header admission
was followed by only 128 bodies, with four outstanding requests. Sync now
prefers bodies when the lead exceeds 4096, after requiring an initial header
response from each connection. The production window is configurable, default
16, maximum 16; the existing 32 MiB receive budget remains enforced. Queue
budget failures reduce the window to 8 then 4. Local body processing time no
longer consumes deadlines for other pending responses.

Mining reports its concrete waiting reason every 30 seconds. Peer readiness is
checked independently of versioned validation, and peer loss invalidates jobs.
Tests cover windows 1/4/16 with reordered responses, local-processing timeout
accounting, body backlog scheduling, and peer loss. `mvn -pl rvn-app -am test
-B -q` and package succeeded; app 46 tests, P2P 21, chain 90, storage 31.

Deployed at 20:27 +07, launcher PID 114840 / JVM 114788. CUDA Core vectors and
the local easy-target job passed at 20:27:38. Existing versioned state resumed
without rewind. First two 128-body batches took 22470 and 22049 ms; replay of
the first took about 4.3 seconds. Before this change a cycle also spent about
20 seconds admitting the next 2000 headers. This is an initial observation,
not an extrapolated completion time or a mainnet hashrate measurement.

Known headers: 776000; checked state advanced beyond 34304 while observing the
deployment. The miner remains WAITING for full verified current chainstate.
Historical compatibility boundaries in `rvn-chain/reference/HISTORICAL_VALIDATION.md`
remain applicable; no consensus version change or validation bypass was made.
# 2026-10-24: fair header discovery and measured slow-peer replacement

The previous body-first scheduling could starve headers indefinitely when the
backlog exceeded 4096. A debugger run of the isolated backlog scenario confirmed
the request order. Header requests now run after at most four body batches;
the integration test keeps 257 bodies missing at that boundary and verifies
another getheaders request. Three empty responses from a historical/short peer
now cause rotation even while its body downloads make progress.

Transport retains 64 KiB input/output buffers across handshake and persistent
reading. Multi-item getdata requests refill at half the bounded window; maximum
outstanding blocks (16), maximum payload and 32 MiB receive budget are unchanged.
Tests cover grouped/reordered replies, retained handshake read-ahead and local
processing deadlines. Batch logs separate transport time from body checks and
storage, and report bytes. Transport time is elapsed request time excluding the
receiver callback, not an independent wire-throughput measurement.

After two consecutive slow batches of at least 32 blocks, sync selects the next
peer. Allowance is max(5 seconds, bytes / configured minimum throughput), default
64 KiB/s; zero disables this policy. No local-check/store time is charged to the
peer. An integration test with intentionally delayed responses verified rotation
and retained height 256. Full reactor tests passed (app 47, P2P 21, chain 90,
storage 31), as did packaging.

Real deployment: the IntelliJ mainnet mining configuration was gracefully
restarted through its local Spring admin MBean; current JVM PID 114208.
At 21:17:11 the old peer 79.137.67.110 rotated after 23985/28382 ms transport
on small historical batches. Peer 51.79.161.39 connected at 21:17:13; subsequent
128-body batches showed 65–67 ms transport, 3.6–4.4 seconds body check/store,
and about 4 seconds replay. Headers advanced from 778000 through 794000 and
continued growing, with new 2000-header admission around 0.5 seconds on this peer.
This is evidence for a slow-peer bottleneck, not a claim that buffering alone
caused the improvement. The remaining observed cost is local check/store/replay;
durable HDD force and atomic chainstate commits remain enforced.

The faster observed peer is now first in the YAML defaults. No consensus checks,
validation version, archives or undo history were changed. The node is still in
IBD and the production miner remains WAITING.
# 2026-10-24: Bitcoin sync comparison, headers-first and durable body group commit

Compared Bitcoin's NodeLifecycleService/HeaderSyncCoordinator, bounded 1024-block
scheduler and processInitialSyncBatch chainstate write-back. RVN now defaults to
headers-first discovery, requiring end-of-headers at a recent peer tip before
new body downloads. Stale header endpoints rotate peers. Existing stored bodies
can still replay at startup. Header-only progress does not arm production mining.

Cold archive appendBatch forces each rotated segment and the final segment before
returning references. Header-store group commit validates all supplied bodies,
then atomically publishes references, digests and contiguous cursor with one
sync RocksDB write. Downloader staging is bounded to 128 bodies / 8 MiB, with
partial received data flushed on peer failure. Archive/index formats unchanged;
undo/coin/asset/coverage commits remain per-block. No validator version change.

Full `mvn -pl rvn-app -am package -B -q` passed. Integration tests include both
recent and stale headers-first endpoints. Storage tests cover batch rotation,
reopen, last-body conflicts without partial publication and JVM hard crashes
before/after body index publication (RvnTieredStorageTest: 8 passing tests).

Live benchmark around body height 128000: body check/store fell from about
4 seconds/128 bodies to 39–45 ms; receive/store was 626–755 ms; replay remained
about 4 seconds. End-to-end improvement in these samples is about 1.5–2x,
not an assertion of sustained 130 blocks/s or a full-history completion estimate.

The IntelliJ mining configuration was gracefully restarted through Spring admin.
Benchmark override headers-first=false was temporary, not saved to configuration.
Final JVM PID 119180 runs normal YAML headers-first=true and passed CUDA tests.
Headers advanced above 1220000 while bodies pause at 129197 pending discovery.
The next 2000-header KawPoW batch took about 35 seconds on the CPU light verifier,
an additional computational bottleneck distinct from HDD flushing. Detailed
comparison, limits and measured results: RVN_STAGE/SYNC_PERFORMANCE.md.
# 2026-10-24: full RVN tests and bounded parallel header PoW

Baseline full RVN reactor: 340 tests, zero failures/errors/skips. The ERROR
"validator unavailable" is intentionally produced by RvnMiningServiceTest's
fail-stop scenario; no baseline test failure was reproduced.

Added RvnHeaderPowBatch, a bounded 1..8-thread pool (default 4), per-thread
all-era engine and single-epoch light cache, at most one 2000-header batch.
PoW/mix calculations execute independently; results are consumed in original
order. The existing contextual/target/checkpoint validator and one atomic
header-store commit remain authoritative. Batch interruption/closure cancels
running and queued futures; shutdown races normalize to an I/O cancellation.
No consensus rule, validator version, undo or covered chainstate was changed.

Final `mvn -pl rvn-app -am package -B -q`: 346 RVN tests, zero failures/errors/
skips (app 55, chain 90, consensus 54, P2P 21, PoW 72, protocol 20, storage 34).
Tests exercise concurrency, ordered results, interruption/reuse, shutdown with
queued work, invalid PoW atomic rejection/retry, context checks in both modes,
and real independent KawPoW engines versus serial results with a Core vector.

The previous node process was already absent. Started the IntelliJ mainnet
mining configuration, JVM 112140; CUDA mandatory vectors and local easy-target
job passed at 23:07:35 +07. Historical chainstate resumed at 129197, headers
at 1236000. Warm batches of 2000 KawPoW headers took 10.3–11.6 seconds versus
34–40 seconds previously; an epoch change took 15.8 seconds. JVM stack snapshot
confirmed four active worker threads; observed heap ~660 MiB / 4 GiB limit.
Headers advanced through 1250000. Bodies wait for headers-first completion;
production miner remains WAITING. See SYNC_PERFORMANCE.md for remaining
per-block undo/chainstate bottleneck and scope of these measurements.
# 2026-10-24: larger CPU cache and real GPU header verification

Implemented bounded generic CUDA header verification (binary operation 3),
locally generated runtime instruction tables for different height/3 programs,
and a shared mining/verification CUDA context. GPU computes every header;
Java checks every mix and retains ordered target/context/checkpoint admission.
CPU audits endpoints plus two secure random samples per large epoch group,
all results for small diagnostic groups. GPU errors fail locally; unsupported
old-era/mixed batches use the existing CPU path. Activation waits for mandatory
startup self-tests and ApplicationReadyEvent.

CPU DAG LRU default is now 65536 entries / 16 MiB payload per verifier.
GPU full DAG remains in VRAM, bounded workspace ~2 MiB and reserve >=1 GiB by
default. Generation chunks increased 8192 -> 65536 nodes. A locally generated
immutable light-cache with epoch provenance is reused by the CPU audit engine,
avoiding duplicate construction; reduced test or wrong-epoch caches are rejected.
No consensus/schema/validator-version/coverage changes or archive deletions.

Final full reactor package gate passed: 351 RVN tests, zero failures/errors/skips
(app 59, chain 90, consensus 54, P2P 21, PoW 73, protocol 20, storage 34).
Standalone RTX 4070 diagnostics passed Core vectors, changing programs/epochs,
unsigned boundaries and independent CPU verification of all 2000 GPU outputs.
Final standalone batch: 120.9 ms GPU+sample audit versus 27618.6 ms CPU-all.
Startup diagnostics, including generic batches and local mining job, also passed.

Deployed through the IntelliJ mainnet mining configuration, final JVM 118688.
Warm mainnet batches: about 245–270 ms/2000; epoch-changing batches about
11–11.6 s (formerly ~16 s without shared cache). Observed 24-batch window:
46000 headers / 77.4 s = 594.4 headers/s including epoch preparation/network.
Headers passed 1820000; checked block state remains 129197 during headers-first.
One CUDA subprocess confirmed. Miner still WAITING for full verified chainstate.
See SYNC_PERFORMANCE.md for audit semantics, memory bounds and measurement scope.

2026-10-25 body pass/cache tuning: production JVM 125468 resumed mainnet at checked
1289133. Body/replay pass limit 512, request window default/max 32, coin RocksDB
native block cache default 1024 MiB (configurable 16..4096). Archive group size,
8 MiB staging, receive budgets, synchronous WAL and undo durability unchanged.
Replay elapsed milliseconds now logged. First two real passes: download/store
2219/1888 ms, ordered replay 22424/21590 ms, about 21 blocks/sec end-to-end.
These are nearby historical blocks, not a controlled same-input benchmark; no
claim of fourfold speedup. Main remaining cost is ordered validation/commit.
352 RVN JUnit tests passing across reports; integration fixtures updated to
exercise four 512-block passes and slow-peer rotation after two full passes.
CUDA startup vectors/local-job checks passed after restart; miner still WAITING
for validated genesis-to-tip coverage.

2026-10-27 GPU mining throughput tuning (RTX 4070, current mainnet epoch 613):
Isolated Java -> Python -> CUDA search benchmark, three alternating sweeps,
one second per size per sweep, zero target, no network submission. Core vectors,
nonce/target/overflow checks and CPU verification of diagnostic candidates passed.
Mean MH/s: 4096=11.803, 8192=15.938, 16384=19.228, 32768=21.614,
65536=23.035. Maximum measured 65536-call time 5.649 ms (short-sample result,
not a latency guarantee). Default mining batch changed 4096 -> 65536 in both
Spring fallback and application.yml; RVN_MINING_BATCH_SIZE can still override.
No additional RAM/VRAM allocations, target relaxation or candidate validation
changes. Standalone RvnCudaKawpowBenchmark reproduces tuning without block
submission; stop live miner first to avoid sharing GPU/allocating another DAG.
Mining tests cover nonce ranges and template refresh at the maximum batch size.
Live confirmation 2026-10-27 14:00 +07: JVM 28148, one CUDA worker 3936. 460521472 hashes/19603 ms = 23.49 MH/s, then 712245248/29521 ms = 24.13 MH/s. GPU snapshot 92%, 71.83 C, 191.37 W, total VRAM 7991 MiB. Real mainnet mining resumed at checked/header height 4599147. Targeted mining/service/settings tests and IntelliJ build passed; startup CUDA Core vectors and local-job CPU/native checks passed.

2026-10-27: removed unconditional 30-second production mining job expiry.
Empty-block Mainnet jobs now have no wall-clock TTL; each batch still checks
parent identity, genesis validation readiness, peer freshness and chain-tip age.
Unchanged-parent modern Mainnet DGW and MTP do not require periodic retiming.
Clock rollback invalidates a template only if it exceeds the 720-second future
limit; replacement creation uses the same bound. Nonce exhaustion still creates
a new extraNonce/template. Diagnostic Job deadlines remain supported.
Tests prove continuous generation/digest/nonce at +31 and +330 seconds (64 and
65536 batches), invalidation on tip change, chain staleness, readiness loss and
future-time boundary/rollback. Debugger observed generation=1 and nonce
0,64,128,192 at time offsets 0,0,31,330. Mining tests and IDE build passed.
Thirty-second statistics reporting is unchanged; session counters reset on restart.

2026-10-27 target-hit/relay diagnostics and lower switching latency:
RVN mining stats now separate targetCandidatesSession (CPU-verified candidates
from completed non-overflow search responses), staleCandidatesSession,
minedBlocksSession (durable local admission), relayedBlocksSession (relay method
completed), admissionFailuresSession and relayFailuresSession. Counters live in
RvnSoloMining and are included in fatal error logs even when advance() throws.
Candidate discovery and local admission/relay completion also log immediately.
Overflow retries do not count discarded partial responses; counters are session
only. Successful production relay confirms peer possession, not network-wide
consensus acceptance or confirmations. Recovery of a prior session's outbox
inside backend startup is not counted as a new mined block in this session.
No sleep after a stale GPU result; only a 1 ms backoff for stale jobs before GPU
work and the existing 250 ms WAITING backoff. Header poll default is 2000 ms,
overridable with RVN_SYNC_POLL_MS. Mining/service and body integration tests pass;
assertions cover stale target hits, admission failure and relay retry without
counting a locally accepted block twice. IDE build/inspections pass.
Final deployment: 2026-10-27 23:55 +07 JVM 95560. WAITING backoff reduced to 25 ms (supersedes the earlier 250 ms note), no post-GPU STALE sleep. Local CUDA job diagnostics suppress production candidate/admission/relay event logs and use their own counters. Startup Core/local-job self-tests pass. At 23:57:14, header -> SEARCHED 128 ms, replay completion -> SEARCHED 4 ms. At 23:57:43 live stats 23.965 MH/s, all candidate/admission/relay counters zero. Header polling configured at 2000 ms.
