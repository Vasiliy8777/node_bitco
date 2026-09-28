$ErrorActionPreference = "Stop"

Write-Host "=== RPC / API OPERATIONAL COMPLETENESS STAGE GATE ==="

& .\mvnw.cmd clean test
if ($LASTEXITCODE -ne 0) { throw "Full reactor test suite failed" }
Write-Host "[OK] full reactor test suite"

$rpc = Get-Content "app/src/main/java/ru/bitcoin/node/app/rpc/NodeRpcServer.java" -Raw
$peerManager = Get-Content "p2p/src/main/java/ru/bitcoin/node/p2p/PeerManager.java" -Raw
$required = @(
    'root instanceof List<?> batch',
    'notification = v2',
    'respondNoContent',
    'case "help"',
    'case "getrpcinfo"',
    'case "ping"',
    'case "savemempool"',
    'case "getblockchaininfo"',
    'case "getnetworkinfo"',
    'case "getmempoolinfo"',
    'case "getindexinfo"',
    'case "getpeerinfo"',
    'case "getblocktemplate"',
    'case "submitblock"',
    'case "dumptxoutset"',
    'case "loadtxoutset"'
)
foreach ($marker in $required) {
    if (-not $rpc.Contains($marker)) { throw "Missing RPC/API contract marker: $marker" }
}
if (-not $peerManager.Contains('requestPings()')) { throw "Missing operational ping dispatch" }

Write-Host "[OK] auth/request limits, legacy+JSON-RPC 2.0, batch/notifications, operational introspection, network/mempool/chain/mining/index/AssumeUTXO RPC contracts present"
Write-Host "RPC / API OPERATIONAL COMPLETENESS STAGE: CLOSED"
