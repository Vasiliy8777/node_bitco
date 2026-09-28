# Crash / Recovery / Resource / DoS Hardening — Stage 106

Closing contract for bounded-resource and restart safety.

The reactor suite must retain coverage for synchronous atomic RocksDB chain-state batches, reorg/failure recovery, AssumeUTXO crash recovery, persistent mempool checkpoint retry/shutdown, pruning safety, bounded P2P payload/parser/write budgets, inbound connection/handshake limits and timeouts, header/block download timeouts, address relay budgets, orphan/compact-block bounds, RPC request/concurrency bounds, bounded relay work/bytes, and bounded BIP157 serving.

Stage 106 adds a per-peer inbound relay byte budget so one peer cannot consume the global relay queue, disconnects peers that saturate relay work, and turns BIP157 queue overflow from silent loss into explicit resource-abuse handling.
