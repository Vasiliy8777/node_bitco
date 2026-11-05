# Initial NoBan header timeout — Core 32.1

Reference: [Core initial header timeout handling](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp#L5970-L5999).

The initial-sync I/O failure handler used to unregister and close NoBan peers
even for `HeaderSynchronizationTimeoutException`. It now excludes that endpoint
from the next race while retaining the open connection in PeerManager. It does
not fabricate a HEADERS acknowledgment or release request admission. Ordinary
timeout, NoBan transport failure and already-closed connection cleanup remain.

Three added lifecycle cases cover NoBan timeout, ordinary timeout and NoBan
transport failure. They verify exact close/unregister behavior, endpoint
exclusion, successful replacement supervision, block synchronization and RUNNING.
The NoBan-timeout case failed before the production change. Existing header
rejection and internal-error cases remain in the same fixture.

IntelliJ evidence on November 5 at 14:22 Bangkok captured an open NoBan test peer
at old `handleHeaderSyncPeerFailure:691`, before `peer.close()`, with an actual
`HeaderSynchronizationTimeoutException`. Stack:
`start:327 -> synchronizeHeadersWithFailover:614 -> handleHeaderSyncPeerFailure:691`.
Evaluation had no breakpoint errors; only the test JVM was paused. Agent
breakpoints were removed after capture. Baseline:
`target/core-update-audit/core32-initial-noban-timeout-before.log`.

All 53 selected cases passed with zero skips/failures/errors: initial lifecycle,
catch-up/runner, live rejection/timeout, PRESYNC/database admission, coordinator
and real peer-access checks. Log:
`target/core-update-audit/core32-initial-noban-timeout-focused.log`.
Packaging succeeded with tests skipped after that run on November 5 at 14:25:33
Bangkok: `target/core-update-audit/core32-initial-noban-timeout-package.log`.

This is the specific NoBan retention rule. Core's complete initial deadline
calculation, preferred-peer accounting and timeout-trigger conditions remain
open; the current live five-second cap and initial response timeout are not
claimed to match all Core timing. Manual-only bootstrap remains separate work.

Live verification on November 5 at approximately 14:29 Bangkok: original IDEA
`BitcoinNodeApplication GPU`, exactly one Bitcoin process (PID 120452), RUNNING,
height/headers 974638, IBD false, eight connections including two block-relay
peers. Two anchors loaded and RPC showed zero privileged peers. CUDA logged
accepted shares at approximately 2.54 GH/s. Initialization took 149 seconds.
Evidence: `target/core-update-audit/core32-initial-noban-timeout-live-verification.json`.
