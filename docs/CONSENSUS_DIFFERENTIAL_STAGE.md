# Consensus Differential / Testing stage completion

This stage has two mandatory gates. It is not CLOSED until both pass.

1. `./mvnw.cmd clean test` — fixed Bitcoin Core/BIP corpus plus deterministic flag mutations.
2. `tools/consensus-certify-core31.ps1` — live Java-node vs Bitcoin Core 31.x differential on an already synchronized regtest chain.

The live gate compares chain tip/chainwork, raw headers, raw blocks and `gettxoutsetinfo hash_serialized_3`; submits 64 deterministic malformed block mutations; checks unknown-parent rejection; then performs an invalidate/reconsider disconnect round-trip and compares the full exposed chain/UTXO state after both transitions. A `finally` block restores an invalidated reference chain if certification aborts.

## Preconditions

- Java node and Bitcoin Core 31.x must both be running on regtest and synchronized to the same tip.
- Both RPC endpoints must be reachable with the credentials supplied to the script.
- The chain must contain at least `ReorgDepth + 1` blocks.
- Run on disposable/regtest data only: certification intentionally calls `invalidateblock` and `reconsiderblock`.

## Full gate

```powershell
.\tools\consensus-stage-gate.ps1 -JavaRpc http://127.0.0.1:<JAVA_RPC_PORT> -CoreRpc http://127.0.0.1:18443 -JavaUser <JAVA_USER> -JavaPassword <JAVA_PASSWORD> -CoreUser <CORE_USER> -CorePassword <CORE_PASSWORD>
```

A green Maven suite alone does not close the stage. The final `CONSENSUS DIFFERENTIAL/TESTING STAGE: CLOSED` line is the completion criterion.
