# Consensus differential/testing stage

This stage turns the existing Bitcoin Core/BIP fixtures into a reproducible conformance gate.

## Always-on Maven gate

`./mvnw clean test` executes:

- all 1212 projected Bitcoin Core script vectors, including witness/Taproot rows;
- all 120 Core tx_valid and 93 tx_invalid rows;
- Core-style flag monotonicity/minimality/maximality checks for every transaction vector;
- 16 deterministic mixed flag combinations per transaction vector;
- all 500 legacy sighash vectors;
- official BIP340 verification vectors;
- BIP341 key-path sighash/spend vectors;
- existing block, contextual transaction, locktime, sequence-lock, PoW, difficulty,
  deployment, witness-commitment, BIP30/BIP34 and Taproot/Tapscript regression tests.

External corpora are SHA-256 pinned. Updating a fixture requires an explicit re-pin, so an
upstream data change cannot silently reduce or alter coverage.

The Core transaction corpus is intentionally kept under its truthful `bitcoin-core-v30`
resource name until v31 upstream bytes are imported and verified. The conformance harness
itself mirrors current Core transaction_tests.cpp flag invariants and is not version-specific.

## Live Core-vs-Java state differential

Start Bitcoin Core and the Java node on the same network/chain state, then run on Windows:

```powershell
.\tools\consensus-differential.ps1 `
  -JavaRpc http://127.0.0.1:8332 `
  -CoreRpc http://127.0.0.1:18332 `
  -JavaUser bitcoin -JavaPassword bitcoin `
  -CoreUser bitcoin -CorePassword bitcoin
```

Use the actual RPC endpoints/credentials. The script fails immediately on a difference in
chain identity, height, best block, chainwork, selected block hashes, or UTXO
`hash_serialized_3`/count/amount checkpoint.

For regtest differential scenarios, feed the same accepted block/transaction sequence to
both nodes, then run this checkpoint after every scenario boundary (mining, RBF/package,
reorg, invalidate/reconsider, restart, snapshot/pruning). A mismatch is a hard failure.
