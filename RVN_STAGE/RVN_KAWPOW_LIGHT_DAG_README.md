# RVN KawPoW — lazy DAG access patch

Source snapshot: `node_bitco-main - 2026-10-23T230236.067.zip`.

## Changes

- `RvnKawpowDagReader`: typed 128-byte DAG random-access contract.
- `RvnKawpowLightDag`: on-demand dataset item generation from the existing light cache; bounded LRU with thread-safe access, defensive copies, strict bounds, cache statistics and cache clearing.
- `RvnKawpowDagLookup`: unsigned 32-bit item indices, 32-bit little-endian word access and strict provider validation.
- `RvnLightDagAccessTest`: five JUnit tests for existing dataset equivalence, LRU eviction, defensive copying, unsigned indexing, invalid providers and concurrent reads.

## Validation performed

- All `rvn-protocol`, `rvn-consensus`, and `rvn-pow` production Java sources compiled together using JDK 21 `javac --release 21`.
- Independent smoke test generated and compared five 128-byte DAG items to the existing dataset builder and checked unsigned indexing.
- Full Maven/JUnit suite not executed in this environment (Maven/JUnit unavailable).

## Security and limitations

This is **not** a complete KawPoW implementation. The ProgPoW program generator, lane initialization, DAG access scheduling, mixing, final proof and authoritative Ravencoin Core consensus vectors remain outstanding. No production consensus claims are made. `RvnKawpowLightDag.forEpoch` allocates the full light cache (~16 MiB at epoch zero), but **does not** allocate the full DAG (~1 GiB). Lazy generation may be CPU-intensive; cache capacity should be bounded based on the node's memory budget.

Unpack at the repository root. Run:

```powershell
mvn -pl rvn-pow -am test
mvn -pl rvn-app -am test
```
