# Pruning schema checks during IBD

Payload stores previously constructed a new `RocksDbPruneUsageStore` for every
save, delete and size query. Each size update entered the database monitor and
read the pruning schema marker, even when migration had already completed.

Block and undo stores now retain their pruning usage store. Its successful
migration state is published through a volatile flag only after the supported
marker has been checked or the migration and durable marker write have succeeded.
The initial check is serialized on the database monitor. A new store after a
database reopen checks the marker again. Explicit `ensureMigrated()` still checks
the database; errors are never cached. Normal payload deletion, pruning and undo
namespace clearing do not remove the independent schema marker.

Only schema readiness is cached. Payload sizes, availability, undo data, UTXO
values and validation results retain their existing persistence semantics.
No consensus checks or durability operations are removed.

Regression tests verify zero database gets for 256 size updates after successful
initialization, the final sizes after reopening, and retry after an unsupported
schema marker. Existing tests cover payload deletion, undo clearing and migration
from old markers.

Validation log: `target/ibd-core-policy/prune-schema-ready-full-tests.log`.
Full reactor: BUILD SUCCESS, 3944 tests across 361 classes, zero failures or
errors, six skips; includes the configured local Bitcoin Core integrations.
This change removes administrative database reads; its effect on sustained live
IBD throughput has not been measured. It does not establish speed parity with
Bitcoin Core. Prior live diagnostics still identify cold primary block index and
UTXO reads as substantial costs.
