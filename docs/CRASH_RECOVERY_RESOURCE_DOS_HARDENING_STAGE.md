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
was killed inside the native RocksDB write. A process kill does not model power
failure, lost disk cache writes, disk-full or media corruption.

On Windows the harness waits up to five seconds for exclusive file sharing on LOCK
to be released after process termination. It does not delete LOCK, retry RocksDB
recovery or repair the database. Failure to regain file access fails the test.

`crash-recovery-resource-dos-stage-gate.ps1` now requires an executed, non-skipped
Surefire report for these scenarios. Its success means automated checks passed.
The entire stage remains OPEN pending power-loss, disk I/O fault injection and
sustained resource-load evidence; source-file existence alone cannot close it.

## Spending and multi-block disconnect/reconnect (30 September 2026)

The fixture now covers twelve named scenarios: before/after/racing each of ordinary
block submission, submission of two dependent spending transactions, disconnect of
three blocks, and reconnect of those blocks. A coinbase at height 1 is matured by
height 101. At height 102 its output is spent, and the resulting output is spent
again within the same block. Invalidation at height 101 rolls back heights 101–103;
reconsideration reactivates the same chain. The reference independently reconstructs
the same scenario and must match the recovered old or new state, including the
persistent invalidation root.

Besides the aggregate UTXO digest, assertions check the original/intermediate/final
outpoints, amounts, heights, scripts and coinbase flags. The intermediate output
must never survive. Expected surviving amounts and heights are asserted explicitly,
not solely compared with the reference implementation of the same node.

The gate checks the named scenarios, zero failures/errors and zero skips.
Snapshot finalization, deterministic injection inside commit, disk I/O errors
and power failure remain open.

## Competing branch activation (30 September 2026)

Three additional scenarios terminate the child before, after or racing activation
of a branch with greater chainwork (15 crash scenarios total). Two branches fork
after height 101 and spend the same mature coinbase differently. Branch A is active
at height 103. Branch B's heights 102 and 103 are stored as context-pending without
changing the active tip; submission of B104 triggers activation. The alternative
blocks are built in a separate temporary database with their own UTXO view.

After recovery the harness requires either the complete A103 state or the complete
B104 state, including the exact surviving output, amount and height. Outputs from
the losing branch and intermediate spends must not appear in the recovered UTXO
set. Raw blocks, undo availability and chainwork are compared with the reference.
If A remains active, B104 is resubmitted before the existing invalidate/reconsider
and further-block checks. A racing kill may happen outside the native commit;
these tests do not claim deterministic failure injection inside RocksDB.
