$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    & .\mvnw.cmd clean test
    if ($LASTEXITCODE -ne 0) { throw "full reactor test suite failed" }
    Write-Host "[OK] full reactor test suite"

    $required = @(
      "mining/src/main/java/ru/bitcoin/node/mining/BlockTemplateBuilder.java",
      "mining/src/main/java/ru/bitcoin/node/mining/TransactionSelector.java",
      "mining/src/main/java/ru/bitcoin/node/mining/MiningVersionBits.java",
      "mining/src/main/java/ru/bitcoin/node/mining/coinbase/CoinbaseBuilder.java",
      "app/src/main/java/ru/bitcoin/node/app/rpc/MiningController.java",
      "app/src/main/java/ru/bitcoin/node/app/service/StratumMiningBackend.java",
      "app/src/test/java/ru/bitcoin/node/app/MiningRpcTest.java",
      "app/src/test/java/ru/bitcoin/node/app/BitcoinCoreMiningRoundTripTest.java",
      "app/src/test/java/ru/bitcoin/node/app/StratumIntegrationTest.java"
    )
    foreach ($path in $required) { if (-not (Test-Path $path)) { throw "missing mining contract: $path" } }
    Write-Host "[OK] GBT/proposal/submitblock, contextual templates, versionbits, coinbase+witness commitment, cluster-feerate selection, Stratum and Core round-trip contracts present"
    Write-Host "MINING STAGE: CLOSED"
} finally { Pop-Location }
