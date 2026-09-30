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

## Rejected native writes (30 September 2026)

`NodeStorageFailureTest` adds ten scenarios: IOError and IOError/NoSpace at the
RocksDB write boundary for spending-block commit, disconnect, reconnect, competing
fork activation and header-batch submission. The database and node are real; a
test-only delegate rejects the native write before it executes. Assertions compare
all persisted namespaces and their generation counters, chain/UTXO state and header
publication. Removing the fault must permit retry, reopening and further transitions.

These checks exposed availability migration writing metadata outside the pending
batch. Batch preparation now derives legacy flags without persisting them separately;
the metadata becomes durable with the batch. A storage regression test also cancels
an uncommitted batch, reopens the database and retries while preserving legacy flags.

The gate requires all ten failure scenarios without skips. Pass `-CoreBinary` with
the path to `bitcoind` to include the Bitcoin Core roundtrip in its `clean test` run.
Injected pre-write rejection does not simulate actual disk exhaustion, a partial WAL
write, an error with uncertain commit outcome, power loss or sustained resource load.
Those checks, along with snapshot-finalization fault coverage, remain open.

## Initialization write rejection (30 September 2026)

Four more `NodeStorageFailureTest` cases reject genesis initialization and legacy
best-header migration with IOError or IOError/NoSpace (14 failure cases total).
Rejected genesis writes must leave the database empty, including after reopening.
Rejected migration must preserve every persisted key and namespace generation.
Both operations are retried after reopening, then reopened again and compared with
an independently initialized node, including its complete persisted contents.
The existing initializer passed these checks without production changes. The stage
gate now requires both new scenario names. These remain pre-write rejection tests,
not partial native-write or power-loss simulations.

## AssumeUTXO promotion write rejection (30 September 2026)

Two parameterized cases now exercise IOError and IOError/NoSpace at each of four
native-write boundaries in validated snapshot promotion: canonical clear/marker,
the first 10,000 entries, the remaining entry, and final marker/staging cleanup.
Already successful writes are real RocksDB commits. After rejection, staging and
activation metadata must remain available; reopening and retrying must promote all
10,001 entries, remove old canonical entries and metadata, and preserve the active
tip. A subsequent finalization is a no-op. Opaque key/value fixtures exercise the
storage copy protocol, not snapshot validation or coin decoding.

The gate requires 16 storage-failure test cases. This covers deterministic rejected
writes during promotion, but not native partial writes, process termination during
snapshot finalization, actual filesystem errors or power loss.

## Process termination during snapshot promotion

`SnapshotProcessCrashTest` pauses a child JVM before promotion or immediately after
one of the four native synchronous writes, then forcibly terminates it with its
database open. The test-only RocksDB delegate performs the real write before the
barrier; no production crash hook is added. Startup runs through the production
`NodeValidationService` constructor, including finalization before `ChainInitializer`.

The fixture uses 10,002 actual UTXOs from two accepted regtest blocks. Snapshot
activation/background-validation markers are installed by the harness; this does
not test snapshot import or historical background validation. The active tip is one
block beyond the snapshot base. Before recovery, assertions distinguish the empty,
10,000-entry and fully copied canonical namespaces and remaining markers. Recovery
must match an independently built reference chain, including the UTXO digest,
count/value and chainwork. Invalidate/reconsider exercises recovered undo; another
block is mined and the database reopened again.

The gate requires all five named scenarios without failures or skips. These are
deterministic process kills between native commits, not kills inside native writes,
power-loss simulations or real filesystem/WAL fault injection.
