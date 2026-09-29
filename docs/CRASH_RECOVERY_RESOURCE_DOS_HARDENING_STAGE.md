# Crash / Recovery / Resource / DoS Hardening — Stage 106

Closing contract for bounded-resource and restart safety.

The reactor suite must retain coverage for synchronous atomic RocksDB chain-state batches, reorg/failure recovery, AssumeUTXO crash recovery, persistent mempool checkpoint retry/shutdown, pruning safety, bounded P2P payload/parser/write budgets, inbound connection/handshake limits and timeouts, header/block download timeouts, address relay budgets, orphan/compact-block bounds, RPC request/concurrency bounds, bounded relay work/bytes, and bounded BIP157 serving.

Stage 106 adds a per-peer inbound relay byte budget so one peer cannot consume the global relay queue, disconnects peers that saturate relay work, and turns BIP157 queue overflow from silent loss into explicit resource-abuse handling.

## Process-crash checks (29 September 2026)

`NodeProcessCrashTest` starts the actual `BitcoinNodeApplication` Spring configuration
in a separate JVM, with a temporary regtest database and network/RPC/Stratum disabled.
The parent forcibly terminates that process before submission, after acknowledged
submission, or racing submission of the next deterministic block. No normal close or
shutdown hook is used for the crashed database. After reopening, the test runs the
startup chainstate consistency checker and compares tip, chainwork, raw blocks, UTXO
digest/count/value and undo availability with an independently generated reference.
It also invalidates/reconsiders the recovered tip and connects another block.

The racing case accepts either complete state; it does not establish that the process
was killed inside the native RocksDB write. The fixture uses coinbase-only blocks;
crashes inside spending transactions, multi-block reorg and snapshot finalization
remain separate scenarios. A process kill does not model power failure, lost disk
cache writes, disk-full or media corruption.

On Windows the harness waits up to five seconds for exclusive file sharing on LOCK
to be released after process termination. It does not delete LOCK, retry RocksDB
recovery or repair the database. Failure to regain file access fails the test.

`crash-recovery-resource-dos-stage-gate.ps1` now requires an executed, non-skipped
Surefire report for these scenarios. Its success means automated checks passed.
The entire stage remains OPEN pending power-loss, disk I/O fault injection and
sustained resource-load evidence; source-file existence alone cannot close it.
