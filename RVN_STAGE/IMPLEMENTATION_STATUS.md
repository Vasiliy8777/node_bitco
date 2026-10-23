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
  are stored atomically in the RVN DB with integrity metadata and a separate
  persistent download cursor; header reorgs rewind this cursor while retaining
  cached off-branch bodies. These are candidate bodies, not validated UTXO state.
- RVN P2P accepts up to 8 MB per frame, with a 32 MiB inbound queue byte budget
  in addition to the existing message-count limit.
- `ravencoin.mining.address: ${RVN_MINING_ADDRESS:}` binds the requested receiving
  address. Base58Check checksum and network/type (RVN P2PKH/P2SH) are validated.
  The parsed payout object retains only the script, not the original address;
  neither successful startup nor validation errors print the address.
- Coinbase assembly supports the configured payout script, BIP34 height,
  extraNonce, RVN's 5000-coin subsidy / 2,100,000-block halvings and explicit fees.
  Complete block-template/witness-commitment assembly is still pending.

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

Headers live in `data/ravencoin/mainnet/headers`, independently of Bitcoin.
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
keeps mining disabled. Bodies currently share the independent RVN RocksDB with
header records under separate key prefixes; full body retention consumes disk.

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
3. Full transaction/block validation, scripts, UTXO and RVN assets state,
   deployment rules, block undo and atomic recovery/reorganizations.
4. Mempool, validated fee accounting, complete block template assembly (including
   witness commitment), candidate submission and relay. Basic payout/coinbase
   assembly is implemented.
5. GPU KawPoW worker, job cancellation on tip changes, local candidate
   validation and network submission.
6. End-to-end tests with independently accepted blocks, then enable the
   production mining lifecycle in the dedicated Spring Boot launcher.

## Reference sources

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
