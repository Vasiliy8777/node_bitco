# NoBan header-timeout failover — Core 32.1

Reference: [Core header-sync timeout handling](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp#L5970-L5999).

Core can reset header synchronization and select another source without closing
a NoBan connection. The Java live synchronizer previously closed every peer on
`HeaderSynchronizationTimeoutException`, including locally configured NoBan peers.

The live failure handler now retains an open NoBan peer for this specific header
timeout and continues the current ready-peer iteration. It does not acknowledge
an unanswered GETHEADERS or reset the per-peer admission deadline. Transport
errors, block download failures and already-closed connections retain their
existing cleanup. MANUAL alone does not imply this NoBan exception.

The regression fixture failed before the production change because `close()`
was called on NoBan. IntelliJ debugger evidence on November 5 at 01:55 Bangkok
confirmed `replaceFailedPeer:167` was reached with a NoBan condition and an actual
`HeaderSynchronizationTimeoutException` (100 ms fixture timeout). Captured stack:
`LiveChainSynchronizer.run:127 -> replaceFailedPeer:167`; tracepoint evaluation
returned the exception type without breakpoint-expression errors. The short
test session was held at that location after non-suspending session events were
lost on termination. The production process was stopped before compiling tests;
no production debugging or state mutation was performed.

`LiveHeaderTimeoutTest` covers ordinary timeout, NoBan timeout, MANUAL-only timeout
and NoBan transport failure. Every case verifies failover to a responsive peer,
successful block synchronization/freshness and no fabricated header-response
acknowledgment. Related live, initial/coordinator, PRESYNC, asynchronous reception
and manual reconnect tests run together in
`target/core-update-audit/core32-header-timeout-focused.log`.
All 43 selected cases passed with zero skips/failures/errors. Packaging then
succeeded with tests skipped, using that tested source state:
`target/core-update-audit/core32-header-timeout-package.log`.

This closes the live NoBan header-timeout disconnect difference only. Core's
complete initial-header deadline calculation, preferred-peer counters, ordinary
peer timeout policy and manual-only bootstrap are still separate work. The
existing live five-second response cap is not claimed to match all Core timing.

Live verification on November 5 at approximately 02:00 Bangkok: original IDEA
`BitcoinNodeApplication GPU`, exactly one Bitcoin process (PID 115896), RUNNING,
height/headers 974567, IBD false, eleven connections including three block-relay
peers during startup/rotation. Two saved anchors loaded. RPC reported no peers
with assigned permissions. CUDA resumed accepted shares at approximately
2.58 GH/s. Evidence:
`target/core-update-audit/core32-header-timeout-live-verification.json`.
