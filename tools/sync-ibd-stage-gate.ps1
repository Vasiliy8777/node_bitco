$ErrorActionPreference = "Stop"

Write-Host "== Sync / IBD complete-stage gate =="

& .\mvnw.cmd clean test
if ($LASTEXITCODE -ne 0) {
    throw "Full reactor test suite failed"
}
Write-Host "[OK] full reactor test suite"

$required = @(
    "app/src/main/java/ru/bitcoin/node/app/sync/HeaderSyncCoordinator.java",
    "app/src/main/java/ru/bitcoin/node/app/sync/BlockSyncCoordinator.java",
    "app/src/main/java/ru/bitcoin/node/app/sync/LiveChainSynchronizer.java",
    "p2p/src/main/java/ru/bitcoin/node/p2p/sync/BlockDownloadScheduler.java",
    "p2p/src/main/java/ru/bitcoin/node/p2p/sync/SchedulerBlockDownloadSession.java",
    "p2p/src/main/java/ru/bitcoin/node/p2p/sync/BlockInFlightTracker.java",
    "p2p/src/main/java/ru/bitcoin/node/p2p/sync/BlockDownloadTimeoutEvaluator.java",
    "chain/src/main/java/ru/bitcoin/node/chain/InitialBlockDownloadState.java",
    "app/src/test/java/ru/bitcoin/node/app/sync/HeaderSyncStreamingTest.java",
    "app/src/test/java/ru/bitcoin/node/app/sync/BlockSyncCoordinatorTest.java",
    "p2p/src/test/java/ru/bitcoin/node/p2p/sync/SchedulerBlockDownloadReconnectTest.java",
    "chain/src/test/java/ru/bitcoin/node/chain/InitialBlockDownloadStateTest.java"
)
foreach ($path in $required) {
    if (-not (Test-Path $path)) { throw "Missing Sync/IBD contract file: $path" }
}
Write-Host "[OK] headers-first, restart state, download window, multi-peer scheduling, timeout/stall, reconnect/reassignment, reorg and IBD contracts present"
Write-Host "SYNC / IBD STAGE: CLOSED"
