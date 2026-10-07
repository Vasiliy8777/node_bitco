$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$session = Get-Content -LiteralPath (Join-Path $projectRoot 'target/gpu-mining/session.json') -Raw | ConvertFrom-Json
@{ network=$session.network; nodePid=$session.nodePid; minerPid=$session.minerPid;
    nodeRunning=[bool](Get-Process -Id $session.nodePid -ErrorAction SilentlyContinue);
    minerRunning=($session.minerPid -gt 0 -and [bool](Get-Process -Id $session.minerPid -ErrorAction SilentlyContinue)) } |
    ConvertTo-Json -Compress
$rpcUser = if ($env:BITCOIN_RPC_USER) { $env:BITCOIN_RPC_USER } else { 'bitcoin' }
$rpcPassword = if ($env:BITCOIN_RPC_PASSWORD) { $env:BITCOIN_RPC_PASSWORD } else { 'bitcoin' }
$authorization = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${rpcUser}:${rpcPassword}"))
foreach ($method in @('getblockchaininfo', 'getmininginfo', 'getconnectioncount')) {
    $body = @{ jsonrpc='2.0'; id=1; method=$method; params=@() } | ConvertTo-Json -Compress
    $reply = Invoke-RestMethod -Uri "http://127.0.0.1:$($session.rpcPort)" -Method Post -TimeoutSec 10 -Headers @{Authorization="Basic $authorization"} -ContentType 'application/json' -Body $body
    if ($reply.error) { throw ($reply.error | ConvertTo-Json -Compress) }
    @{ method=$method; result=$reply.result } | ConvertTo-Json -Depth 6 -Compress
}
Get-Content -LiteralPath (Join-Path $session.logDirectory 'miner.log') -Tail 6
