$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    Write-Host "== Mempool exactness stage gate =="
    & .\mvnw.cmd clean test
    if ($LASTEXITCODE -ne 0) { throw "Full reactor test suite failed" }

    $required = @(
        "mempool/src/main/java/ru/bitcoin/node/mempool/ClusterLinearization.java",
        "mempool/src/main/java/ru/bitcoin/node/mempool/MempoolGraphPolicy.java",
        "mempool/src/main/java/ru/bitcoin/node/mempool/PackagePolicy.java",
        "mempool/src/main/java/ru/bitcoin/node/mempool/RollingMinimumFee.java",
        "mempool/src/test/java/ru/bitcoin/node/mempool/SingleRbfClusterPolicyTest.java",
        "mempool/src/test/java/ru/bitcoin/node/mempool/PackageAdmissionTest.java",
        "mempool/src/test/java/ru/bitcoin/node/mempool/ReorgTrucPolicyTest.java",
        "mempool/src/test/java/ru/bitcoin/node/mempool/MempoolTestAcceptTest.java",
        "storage/src/main/java/ru/bitcoin/node/storage/mempool/RocksDbMempoolStore.java",
        "storage/src/main/java/ru/bitcoin/node/storage/mempool/RocksDbMempoolFeeDeltaStore.java"
    )
    foreach ($path in $required) {
        if (-not (Test-Path $path)) { throw "Missing mempool stage contract file: $path" }
    }

    $mempool = Get-Content "mempool/src/main/java/ru/bitcoin/node/mempool/Mempool.java" -Raw
    $graph = Get-Content "mempool/src/main/java/ru/bitcoin/node/mempool/MempoolGraphPolicy.java" -Raw
    $package = Get-Content "mempool/src/main/java/ru/bitcoin/node/mempool/PackagePolicy.java" -Raw
    $rpc = Get-Content "app/src/main/java/ru/bitcoin/node/app/rpc/NodeRpcServer.java" -Raw
    foreach ($needle in @("rollingFee.restoreFrom", "txn-mempool-conflict", "allowReplacement", "feeDeltas", "unbroadcast")) {
        if (-not $mempool.Contains($needle)) { throw "Missing Mempool contract: $needle" }
    }
    foreach ($needle in @("strictly", "clusterCount", "replacement-fee", "truc-topology")) {
        if (-not $graph.Contains($needle)) { throw "Missing graph/RBF contract: $needle" }
    }
    foreach ($needle in @("package-rbf-requires-one-parent-one-child", "package-rbf-mempool-ancestor", "missing-ephemeral-spends")) {
        if (-not $package.Contains($needle)) { throw "Missing package contract: $needle" }
    }
    foreach ($needle in @('case "testmempoolaccept"', 'case "submitpackage"', 'case "prioritisetransaction"', 'case "getprioritisedtransactions"', 'case "getmempoolcluster"', 'case "getmempoolfeeratediagram"')) {
        if (-not $rpc.Contains($needle)) { throw "Missing mempool RPC contract: $needle" }
    }
    if ($rpc.Contains("Expected rawtxs array and optional maxfeerate/maxburnamount")) {
        throw "testmempoolaccept still exposes non-Core maxburnamount argument"
    }

    Write-Host "[OK] full reactor test suite"
    Write-Host "[OK] cluster limits/linearization, RBF diagram rules, TRUC/sibling eviction, ephemeral dust, rolling floor, package CPFP/RBF, dry-run semantics, fee deltas, unbroadcast and persistence contracts present"
    Write-Host "MEMPOOL EXACTNESS STAGE: CLOSED"
} finally {
    Pop-Location
}
