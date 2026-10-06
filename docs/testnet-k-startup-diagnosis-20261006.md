# Testnet3 startup using the existing K: database

Launch: BitcoinNodeApplication, explicit testnet profile, data root
`K:/BitcoinJavaNode` (network directory `K:/BitcoinJavaNode/testnet3`).
PID 150144. No deletion, migration, or reset was performed.

Headers reached 5156510. Active blocks remained at 653145. The node entered
SYNCHRONIZING_BLOCKS at 14:35:01.701 +07:00. No frontier submission or scheduler
assignment was logged during the observed interval, while the peer pool reached
eight ready peers.

The paused lifecycle-thread stack was:

```
RocksDbDatabase.get:241
RocksDbBlockIndexStore.find:66
StoredBlockIndexLookup.find:71
BlockSyncCoordinator.connectWindow:438
BlockSyncCoordinator.synchronizeInternal:368
BlockSyncCoordinator.synchronizeToTip:284
NodeLifecycleService.synchronizeBlocks:667
NodeLifecycleService.start:338
```

A non-suspending logpoint at connectWindow:438 confirmed:
firstHeight=653146, lastHeight=685913, current.height=671744,
reversed.size=14170. The branch walk was making progress, not waiting for a
network block or stalled in the block download window detector.

Before submitting any network requests, connectWindow synchronously reads all
32768 block indexes in the materialization chunk, backwards from its end.
This cold database traversal is the proven startup barrier. It cannot be
attributed to peer connection failures: requests have not reached the scheduler.
Debugger pauses and logpoint overhead mean this run is not a clean throughput
measurement of database I/O.

connectWindow's loop also has no ensureNotCancelled check. The shutdown run
reproduced Node lifecycle worker did not stop within 10000 ms. Continuing local
index traversal without observing cancellation is a contributing code path;
other shutdown paths may additionally consume time.

Recommended correction: materialize only enough indexed work to fill the first
download window, submit it promptly, and extend the materialized horizon as
validation advances. Check cancellation during ancestry/index walks. Keep
ancestry and consensus validation intact. This diagnosis did not change code or
demonstrate block reception after the preparation barrier.

Logs: target/ibd-core-policy/testnet-k-live.log and
target/ibd-core-policy/testnet-k-console.log. The diagnostic process was stopped,
agent breakpoints removed, and user breakpoints left unchanged.

## Implemented corrections

The coordinator now initially materializes only downloadWindow+1 indexes (1025
by default), preserving the extra stall-probe index. It extends the path as
ordered validation advances, instead of reading all 32768 indexes before the
first request. Extensions still check that they connect to the prior path.
Materialization and ancestry traversal check cancellation, including the
StoredBlockIndexLookup skip and linear implementations.

The existing K: database subsequently revealed a second startup barrier:
the virtual completion worker was inside BlockFailureResolver.resolve through
CoreBlockDownloadPeerPolicy.canTraverse, checking the requested historical
block's failure ancestry. The lifecycle worker waited on the scheduler monitor.
The thread dump is target/ibd-core-policy/k-repeat-threads.json. The persisted
valid best-header anchor now certifies its own ancestors through branch-safe
ancestor lookup. An anchor is revoked on committed failure-store revision
changes and is not used for hypothetical failures or another branch.

Buffered session shutdown interrupts its worker before waiting for the
delegate's assignment monitor. Failure ancestry walks observe interruption.

80 focused tests passed in incremental-window-verified.log, including first
request ordering, cancellation during preparation, skip/linear cancellation,
valid-anchor branch/revision behavior, and interruption before delegate close.
The first updated K: run was invalidated by rebuilding its shared output
directories while it was running (NoClassDefFoundError); later runs were made
only after the build completed. The old diagnostic PID 67376 required forced
termination after its shutdown had remained stuck; only that process was stopped.

## Final validation on the existing database

The next live run exposed a third barrier in AssumeValidPolicy.cachedAncestorHash:
the first connection prefetched 65536 ancestors under the chain-state lock.
The platform stack is in target/ibd-core-policy/k-fixed-platform.txt. Refills
are now bounded to 128 records and observe interruption, including skip lookup.
An exact branch/hash proof is still required; script-skipping conditions and
all other consensus checks are unchanged.

84 focused tests passed in target/ibd-core-policy/startup-final-tests.log.
AssumeValidPolicyTest additionally bounds the first candidate's index reads.

The final testnet3 run used K:/BitcoinJavaNode/testnet3 without clearing or
reinitializing it. Header synchronization finished at 19:27:45; block requests
started 57 ms later. Across the corrected runs, active chain height advanced
from 653145 to 655708 (2563 connected blocks); the final run resumed at 653147.
A measured pipeline interval processed 512 blocks
in 6098 ms of validation, with downloads continuing independently.
Evidence: target/ibd-core-policy/testnet-k-final.log and
target/ibd-core-policy/testnet-k-final-console.log.

Graceful shutdown ran from 19:28:42.605 to 19:28:47.530 (4.925 seconds),
without a lifecycle-worker timeout; PID 176052 exited. The diagnostic node
is stopped. This verifies removal of the reproduced startup barriers, not
complete Core equivalence or uninterrupted throughput for all peers and disks.
