$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    & .\mvnw.cmd clean test
    if ($LASTEXITCODE -ne 0) { throw "full reactor test suite failed" }
    Write-Host "[OK] full reactor test suite"

    $required = @(
      "storage/src/main/java/ru/bitcoin/node/storage/utxo/UtxoSnapshotReader.java",
      "storage/src/main/java/ru/bitcoin/node/storage/utxo/UtxoSnapshotWriter.java",
      "storage/src/main/java/ru/bitcoin/node/storage/utxo/UtxoSnapshotVerifier.java",
      "storage/src/main/java/ru/bitcoin/node/storage/utxo/UtxoSnapshotStager.java",
      "storage/src/main/java/ru/bitcoin/node/storage/utxo/RocksDbSnapshotChainStateStore.java",
      "storage/src/main/java/ru/bitcoin/node/storage/utxo/RocksDbAssumeUtxoBackgroundStore.java",
      "storage/src/main/java/ru/bitcoin/node/storage/utxo/RocksDbAssumeUtxoFinalizer.java",
      "app/src/main/java/ru/bitcoin/node/app/AssumeUtxoBackgroundValidator.java"
    )
    foreach ($file in $required) { if (-not (Test-Path $file)) { throw "missing contract file: $file" } }

    $node = Get-Content "app/src/main/java/ru/bitcoin/node/app/NodeValidationService.java" -Raw
    foreach ($token in @("dumpUtxoSnapshot", "loadUtxoSnapshot", "chainStates", "assumeUtxoPruneCeiling", "ensureBackgroundValidationWorker")) {
      if (-not $node.Contains($token)) { throw "missing AssumeUTXO/storage contract: $token" }
    }
    $finalizer = Get-Content "storage/src/main/java/ru/bitcoin/node/storage/utxo/RocksDbAssumeUtxoFinalizer.java" -Raw
    if ($finalizer.Contains("tips.saveActiveTipHash(batch, snap.snapshotBaseHash())")) {
      throw "unsafe finalizer regression: snapshot base overwrites advanced active tip"
    }
    Write-Host "[OK] snapshot codec/trust, isolated staging, atomic activation, background validation/download, restart finalization, pruning protection and advanced-tip preservation contracts present"
    Write-Host "ASSUMEUTXO / STORAGE STAGE: CLOSED"
} finally { Pop-Location }
