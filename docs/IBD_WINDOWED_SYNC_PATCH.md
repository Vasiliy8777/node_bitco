# Windowed IBD block synchronization patch

Baseline: node_bitco-main - 2026-09-29T195931.471.zip

Changes:
- BlockSyncCoordinator no longer materializes the full ReorganizationPlan connect path during IBD.
- The connect path is materialized in bounded downloadWindow chunks (default 1024).
- Each chunk end is reached through BlockIndexAncestorLookup skip traversal when available.
- Only the current chunk is walked linearly and retained for production synchronization.
- Added synchronizeToTip() for lifecycle/live production callers so a multi-million-entry result list is not retained.
- Existing synchronize()/synchronize(maxBlocks) APIs are preserved for tests and bounded callers.
- Consensus validation, block ordering, stall detection, download session semantics and reorg processing are unchanged.

Expected runtime effect:
- After header sync, first GETDATA/block work should start after one bounded window is materialized instead of after building a ~5.15M-index list.
- Production connect-path memory becomes O(downloadWindow), excluding storage/cache internals.

Validation:
1. .\\mvnw.cmd -pl app -am test
2. .\\mvnw.cmd test
3. Start testnet from active height 0 with fully synced headers and verify BLK starts advancing promptly.

Note: Maven could not be executed in the packaging environment because the wrapper attempted to download Maven 3.9.16 and outbound network access is unavailable. Run the commands above locally before runtime testing.
