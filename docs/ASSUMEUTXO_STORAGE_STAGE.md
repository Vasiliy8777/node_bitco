# AssumeUTXO / Storage stage closure

This stage is closed when the full reactor and `tools/assumeutxo-storage-stage-gate.ps1` pass.

Covered contracts:
- Bitcoin Core v2 UTXO snapshot read/write metadata and network binding.
- trusted AssumeUTXO base/hash_serialized_3 verification before activation.
- isolated RocksDB snapshot staging and incomplete-import recovery.
- atomic persisted snapshot-chainstate activation.
- coexistence of snapshot and historical chainstates via `getchainstates`.
- restart-safe historical background validation with missing-block download.
- INVALID rollback and VALIDATED restart-only namespace promotion.
- promotion preserves an active snapshot tip that advanced beyond the snapshot base.
- inconsistent VALIDATED background metadata is rejected instead of promoted.
- automatic/manual pruning remains bounded by historical validation progress.
- dump/load RPC and storage namespaces remain covered by the full reactor suite.
