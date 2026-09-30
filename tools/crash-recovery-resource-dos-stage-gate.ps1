param([string]$CoreBinary)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    $testArguments = @('clean', 'test')
    if (-not [string]::IsNullOrWhiteSpace($CoreBinary)) { $testArguments += "-Dbitcoin.core.binary=$CoreBinary" }
    & .\mvnw.cmd @testArguments
    if ($LASTEXITCODE -ne 0) { throw "full reactor test suite failed" }
    $required = @(
      'storage/src/test/java/ru/bitcoin/node/storage/rocksdb/RocksDbWriteBatchTest.java',
      'chain/src/test/java/ru/bitcoin/node/chain/BlockFailureRecoveryTest.java',
      'app/src/test/java/ru/bitcoin/node/app/NodeProcessCrashTest.java',
      'storage/src/test/java/ru/bitcoin/node/storage/utxo/RocksDbAssumeUtxoFinalizerTest.java',
      'p2p/src/test/java/ru/bitcoin/node/p2p/PeerWriteBudgetTest.java',
      'p2p/src/test/java/ru/bitcoin/node/p2p/BitcoinServerTest.java',
      'protocol/src/test/java/ru/bitcoin/node/protocol/serialization/ParserLimitsTest.java',
      'app/src/test/java/ru/bitcoin/node/app/NodeRelayServiceTest.java'
    )
    foreach ($file in $required) { if (-not (Test-Path $file)) { throw "missing hardening contract: $file" } }
    $relay = Get-Content 'app/src/main/java/ru/bitcoin/node/app/service/NodeRelayService.java' -Raw
    if ($relay -notmatch 'MAX_QUEUED_INBOUND_BYTES_PER_PEER') { throw 'missing per-peer relay byte budget' }
    $filters = Get-Content 'app/src/main/java/ru/bitcoin/node/app/service/CompactBlockFilterPeerService.java' -Raw
    if ($filters -notmatch 'BIP157 request queue saturated') { throw 'missing BIP157 overload handling' }
    $crashReport = 'app/target/surefire-reports/TEST-ru.bitcoin.node.app.NodeProcessCrashTest.xml'
    if (-not (Test-Path -LiteralPath $crashReport)) { throw 'missing executed process-crash test report' }
    [xml]$crashResults = Get-Content -LiteralPath $crashReport -Raw
    $suite = $crashResults.testsuite
    if ([int]$suite.tests -lt 15 -or [int]$suite.failures -ne 0 -or
        [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) {
        throw 'process-crash recovery tests must execute without failures or skips'
    }
    $requiredCrashCases = @(
        'killBeforeSubmissionPreservesCommittedState', 'killAfterSubmissionPreservesAcknowledgedState',
        'killRacingSubmissionRecoversACompleteState', 'killBeforeSpendPreservesCoins',
        'killAfterSpendPreservesCoins', 'killRacingSpendRecoversCompleteCoins',
        'killBeforeDisconnectPreservesSpentChain', 'killAfterDisconnectRestoresSpentCoins',
        'killRacingDisconnectRecoversCompleteChain', 'killBeforeReconnectPreservesRestoredCoins',
        'killAfterReconnectPreservesSpentCoins', 'killRacingReconnectRecoversCompleteChain',
        'killBeforeForkSwitchPreservesOriginalBranch', 'killAfterForkSwitchPreservesWinningBranch',
        'killRacingForkSwitchRecoversOneCompleteBranch'
    )
    $executedCrashCases = @($suite.testcase | ForEach-Object { $_.name })
    foreach ($case in $requiredCrashCases) {
        if ($case -notin $executedCrashCases) { throw "missing executed crash scenario: $case" }
    }
    $ioReport = 'app/target/surefire-reports/TEST-ru.bitcoin.node.app.NodeStorageFailureTest.xml'
    if (-not (Test-Path -LiteralPath $ioReport)) { throw 'missing executed storage-failure report' }
    [xml]$ioResults = Get-Content -LiteralPath $ioReport -Raw
    $ioSuite = $ioResults.testsuite
    if ([int]$ioSuite.tests -lt 16 -or [int]$ioSuite.failures -ne 0 -or
        [int]$ioSuite.errors -ne 0 -or [int]$ioSuite.skipped -ne 0) {
        throw 'storage-failure tests must execute without failures or skips'
    }
    foreach ($case in @('failedSpendingCommitIsAtomicAndRetryable', 'failedDisconnectDoesNotMarkBranchInvalid',
            'failedReconnectDoesNotClearInvalidation', 'failedForkSwitchPreservesOriginalChain',
            'failedHeaderBatchDoesNotPublishBestHeader', 'failedGenesisInitializationLeavesEmptyDatabase',
            'failedLegacyHeaderMigrationPreservesDatabase', 'failedSnapshotPromotionResumesAtEveryWriteBoundary')) {
        $executed = @($ioSuite.testcase | Where-Object { $_.name.StartsWith($case + '(') })
        if ($executed.Count -lt 2) { throw "missing IOError/NoSpace scenarios: $case" }
    }
    $snapshotReport = 'app/target/surefire-reports/TEST-ru.bitcoin.node.app.SnapshotProcessCrashTest.xml'
    if (-not (Test-Path -LiteralPath $snapshotReport)) { throw 'missing executed snapshot process-crash report' }
    [xml]$snapshotResults = Get-Content -LiteralPath $snapshotReport -Raw
    $snapshotSuite = $snapshotResults.testsuite
    if ([int]$snapshotSuite.tests -lt 7 -or [int]$snapshotSuite.failures -ne 0 -or
        [int]$snapshotSuite.errors -ne 0 -or [int]$snapshotSuite.skipped -ne 0) {
        throw 'snapshot process-crash tests must execute without failures or skips'
    }
    $snapshotCases = @($snapshotSuite.testcase | ForEach-Object { $_.name })
    foreach ($case in @('killBeforePromotion', 'killAfterCanonicalClear', 'killAfterFirstCopyBatch',
            'killAfterLastCopyBatch', 'killAfterCleanupCommit', 'killBeforeInvalidSnapshotRollback',
            'killAfterInvalidSnapshotRollbackCommit')) {
        if ($case -notin $snapshotCases) { throw "missing executed snapshot crash scenario: $case" }
    }
    Write-Host '[OK] full reactor test suite'
    Write-Host '[OK] forced termination at five snapshot promotion and two invalid-snapshot rollback boundaries; startup, UTXO, undo and continued mining checks'
    Write-Host '[OK] forced termination before/after/racing coinbase, dependent spends, disconnect/reconnect and competing-fork activation; chain/UTXO/undo checks'
    Write-Host '[OK] pruning, parser/P2P/RPC bounds, per-peer relay budgets and BIP157 overload contracts present'
    Write-Host '[OK] injected pre-commit IOError/NoSpace preserves state and allows retry'
    Write-Host 'AUTOMATED RECOVERY / RESOURCE CHECKS: PASSED'
    Write-Host 'STAGE REMAINS OPEN: power-loss, real filesystem/WAL I/O faults and sustained resource-load evidence are still required'
} finally { Pop-Location }
