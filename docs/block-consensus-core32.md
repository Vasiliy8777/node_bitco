# Block compatibility with Bitcoin Core 32.1

Reference: [v32.1](https://github.com/bitcoin/bitcoin/releases/tag/v32.1).
Scope: block construction, consensus verification, connection/disconnection and
acceptance of Java-built blocks by Core. Updated 2026-11-01.

## Coverage by rule

| Rule or path | Implementation | Independent evidence and regression coverage |
| --- | --- | --- |
| Header serialization, hash, PoW, compact target | protocol serialization; `ProofOfWork`, `CompactTarget` | Core accepts mined Java headers; unsolved proposals pass but both submissions fail; hash/encoding and compact-target tests |
| Difficulty and retarget | `ChainHeaderValidator`, `NextWorkRequired`, `DifficultyAdjustment` | Header/difficulty unit tests, testnet4 genesis comparison with native Core, BIP94/min-difficulty tests; native blocks reject incorrect bits |
| MTP and future time | `MedianTimePast`, `BlockHeaderValidator` | Native matrix checks equality/strictness at MTP and the exact 7200-second future boundary |
| Block versions and activation flags | `ChainHeaderValidator`, `ConsensusScriptFlags`, `TaprootDeployment` | Native version cases; historical exception, BIP66/BIP65/CSV/SegWit and Speedy Trial tests; matching retrospective Core flags |
| Merkle root, mutation, transaction order | `BlockValidator`, `MerkleTree` | Duplicate transaction, child-before-parent and double-spend cases; Merkle/processor tests |
| Coinbase uniqueness, BIP34, script length | `BlockValidator`, `Bip34Validator`, `CoinbaseBuilder` | Missing/multiple coinbase, wrong height and scriptSig lengths 1/100/101 compared with Core |
| Subsidy and fees | `BlockSubsidy`, `CoinbaseValidator`, contextual input validation | One-satoshi overpayment rejected; underpayment accepted; selected base fees checked against coinbase by GBT metadata builder; subsidy/halving tests |
| Block weight | `BlockWeight`, `BlockTemplateBuilder` | Actual submitted blocks at exactly 4,000,000 weight accepted; excess stripped and witness weight rejected; CompactSize overhead included |
| Sigops | `TransactionSigOpCost`, `BlockSigOpsValidator` | Native legacy boundary 20,000 CHECKSIGs; P2SH 1000/1001 inputs and P2WSH 4000/4001 inputs with 20 static sigops each; exactly 80,000 cost accepted |
| Transaction structure and value ranges | `TransactionValidator`, `ContextualTransactionValidator` | All 121 valid and 93 invalid v32.1 transaction vectors, including flag invariants; native negative/overspent outputs and duplicate-input cases |
| UTXO existence, double spends, maturity | contextual validator; UTXO overlay | Native missing prevout, same-block dependencies, double spends, coinbase maturity at 99/100; Core spend/reorg fixtures |
| BIP30 and historical exceptions | `Bip30`, connect/reorganization builders | Historical hash/height exceptions, proven BIP34 ancestry and resumption at height 1,983,702 have targeted tests |
| Absolute and relative locks | `TransactionFinality`, `SequenceLocks`, ancestor MTP resolver | Native height/time finality, BIP68 height/time/disable bit, version 1 bypass and same-block relative locks; reorg/BIP68 tests |
| Legacy Script, P2SH, SegWit | `InputScriptValidator`, script interpreter and signature hashing | All 1237 v32.1 Script vectors and 500 unchanged upstream sighash vectors; native P2WSH and P2SH spends; policy flags are not imposed on block validation |
| Witness commitment and malleation | `WitnessCommitmentValidator`, `CoinbaseBuilder` | Native wrong witness root/reserved length; correct body with the same header accepted after corrupted witness; oversized future-version witness boundary |
| Taproot/Tapscript | `TaprootValidator`, `TaprootExecutionData`, Schnorr/sighash support | BIP340/BIP341 tests and upstream Script vectors; native true/false, OP_SUCCESS (including unexecuted branches), malformed pushes, mandatory MINIMALIF/cleanstack, annex and future leaf version |
| Future witness versions and consensus/policy separation | witness-program handling, consensus script flags | Core launched with `-acceptnonstdtxn=0` still accepts the valid nonstandard blocks; zero-valued program rejected; nonzero future program and large witness accepted |
| Template selection and resource reservations | cluster linearizer, `TransactionSelector`, `BlockTemplateBuilder` | 384 independent Core C++ optimal-cluster cases; shared-parent RPC comparison; weight/sigops reservation and dependency tests |
| GBT, Stratum and block relay | mining controller, job builder, relay service | Native `BitcoinCoreMiningRoundTripTest` mines a Java template containing a relayed transaction; Core accepts it, then synchronization continues after reconnect; Stratum wire/regression tests |
| Fork selection, undo and durable state | block processor, chainwork selection, reorganization executor | Core reorg/UTXO fixtures, invalidate/reconsider, restart/reindex/crash consistency tests |
| Public signet | `SignetBlockValidator` | BIP325 virtual-transaction/challenge tests and a public-signet block fixture; signet mining requires challenge signing |

## Native block matrix

`BitcoinCoreBlockConsensusTest` starts its own Core 32.1 regtest process with
`-assumevalid=0`, RPC/P2P ports and data directories isolated from the running
node. It imports 101 Core-generated blocks into Java and compares 77 scenarios
through both proposal checks and actual `submitblock`/`processBlock` acceptance.
Valid-target headers are mined even for invalid bodies, so missing PoW cannot
mask a missing body check. An additional test explicitly contrasts proposal
validation and submission for a header without sufficient PoW.

Each accepted candidate is connected on both nodes, then invalidated to restore
the original tip. This checks real state changes as well as return values.
Malformed witness data may leave a header-only entry in Core: GBT can report
`duplicate-inconclusive`, but the repaired block must still pass submission.
No mainnet blocks, coins, wallet or configuration are changed by these tests.

The 77 scenarios plus the separate PoW check passed in
`target/core-update-audit/core32-block-consensus.log`.
There were 36 accepted and 41 rejected matrix candidates; both implementations
agreed on each. The final full Bitcoin/RVN package run completed 5,003 cases:
4,991 executed successfully, 12 opt-in cases skipped, zero failures/errors.
Its log is `target/core-update-audit/core32-block-consensus-full-build.log`.
The upgraded corpus and its provenance are in
`consensus/src/test/resources/bitcoin-core-v32/`; normalized projections are
SHA-256 pinned by `ConsensusCorpusIntegrityTest`.

The full run also exposed two test scheduling assumptions in peer connection/
write cleanup fixtures. The deterministic selector now receives registered
addresses, and the write test waits for both reservations to become reusable.
Debugger probes confirmed a cancelled queued write and an empty write budget
(`count=0`, `bytes=0`); production transport code was unchanged. The focused
19-case regression log is `core32-p2p-fixture-stability.log`.

At 21:26 Asia/Bangkok, authenticated RPC from the existing mainnet process
(PID 50484) reported height/headers 974135, IBD false and 10 connections.
At 21:31 CUDA reported approximately 2.54 GH/s and continued accepting shares.
The live RPC record is
`target/core-update-audit/core32-block-consensus-live-verification.json`.

## What this establishes

No acceptance differences were found in these comparisons. Existing production
consensus code passed the new cases; changing a validated rule without a
demonstrated discrepancy would not improve compatibility. This phase upgrades
the independent evidence and regression checks, rather than inventing a new
consensus rule to match a version number.

The matrix is regtest-based. Historical mainnet activation boundaries, BIP30
exceptions, retargets and public signet additionally rely on targeted tests and
source review. It is not an exhaustive replay of every historical mainnet block
or a proof over every possible Script/transaction graph. Exact rejection text,
header-only caching and budget-limited mempool ordering can differ while block
acceptance agrees. Core's database layout and allocator behavior are separate
implementation details.

Relevant upstream sources:
[validation](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp),
[Script](https://github.com/bitcoin/bitcoin/blob/v32.1/src/script/interpreter.cpp),
[transaction checks](https://github.com/bitcoin/bitcoin/blob/v32.1/src/consensus/tx_check.cpp),
[PoW](https://github.com/bitcoin/bitcoin/blob/v32.1/src/pow.cpp),
[miner](https://github.com/bitcoin/bitcoin/blob/v32.1/src/node/miner.cpp).
