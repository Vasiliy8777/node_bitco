$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    & .\mvnw.cmd clean test
    if ($LASTEXITCODE -ne 0) { throw "full reactor test suite failed" }
    $required = @(
      'storage/src/test/java/ru/bitcoin/node/storage/rocksdb/RocksDbWriteBatchTest.java',
      'chain/src/test/java/ru/bitcoin/node/chain/BlockFailureRecoveryTest.java',
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
    Write-Host '[OK] full reactor test suite'
    Write-Host '[OK] crash recovery, durable atomic state, pruning, parser/P2P/RPC bounds, per-peer relay budgets and BIP157 overload contracts present'
    Write-Host 'CRASH / RECOVERY / RESOURCE / DOS HARDENING STAGE: CLOSED'
} finally { Pop-Location }
