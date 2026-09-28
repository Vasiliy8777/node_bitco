# Sync / IBD stage

This stage is closed only when the full reactor gate is green.

Covered contracts in the current tree:

- headers-first synchronization with block locators and streaming header batches;
- no-progress header response rejection and header response timeout handling;
- persistent best-header/active-chain state used for restart/resume;
- block-body scheduling across READY peers;
- per-peer in-flight limits and a bounded block download window;
- timeout and download-window stall detection;
- reassignment after peer loss, including a temporary zero-peer reconnect window;
- ordered validation despite out-of-order network completion;
- competing-branch download and reorganization to the best header chain;
- local block-body reuse and BIP152 completion into the same scheduler lifecycle;
- latched Initial Block Download state based on minimum chain work and tip age;
- mining readiness is forbidden while IBD is true;
- post-IBD live header/block synchronization and stale-peer replacement;
- node runtime databases are excluded from Git and may be rebuilt from peers.

The app/data directory is runtime RocksDB state. It is not source code. With the
Java node stopped it may be removed completely to force a clean resynchronization.
