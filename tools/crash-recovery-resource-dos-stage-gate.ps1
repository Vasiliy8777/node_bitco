$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    & .\mvnw.cmd clean test
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
    Write-Host '[OK] full reactor test suite'
    Write-Host '[OK] forced termination before/after/racing coinbase, dependent spends, disconnect/reconnect and competing-fork activation; chain/UTXO/undo checks'
    Write-Host '[OK] pruning, parser/P2P/RPC bounds, per-peer relay budgets and BIP157 overload contracts present'
    Write-Host 'AUTOMATED RECOVERY / RESOURCE CHECKS: PASSED'
    Write-Host 'STAGE REMAINS OPEN: power-loss, disk I/O fault injection and sustained resource-load evidence are still required'
} finally { Pop-Location }
