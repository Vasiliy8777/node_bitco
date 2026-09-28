param(
    [Parameter(Mandatory=$true)][string]$JavaRpc,
    [Parameter(Mandatory=$true)][string]$CoreRpc,
    [string]$JavaUser = "bitcoin",
    [string]$JavaPassword = "bitcoin",
    [string]$CoreUser = "bitcoin",
    [string]$CorePassword = "bitcoin"
)
$ErrorActionPreference = "Stop"

function Rpc([string]$url, [string]$user, [string]$password, [string]$method, [object[]]$params=@()) {
    $body = @{jsonrpc="2.0"; id="consensus-differential"; method=$method; params=$params} | ConvertTo-Json -Depth 20 -Compress
    $pair = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("$user`:$password"))
    $r = Invoke-RestMethod -Uri $url -Method Post -Headers @{Authorization="Basic $pair"} -ContentType "application/json" -Body $body
    if ($null -ne $r.error) { throw "$method failed at $url : $($r.error | ConvertTo-Json -Compress)" }
    return $r.result
}

function Same([string]$name, $a, $b) {
    $ja = $a | ConvertTo-Json -Depth 30 -Compress
    $jb = $b | ConvertTo-Json -Depth 30 -Compress
    if ($ja -ne $jb) { throw "CONSENSUS DIVERGENCE [$name]`nJAVA: $ja`nCORE: $jb" }
    Write-Host "[OK] $name"
}

$jc = Rpc $JavaRpc $JavaUser $JavaPassword "getblockchaininfo"
$cc = Rpc $CoreRpc $CoreUser $CorePassword "getblockchaininfo"
Same "chain" $jc.chain $cc.chain
Same "blocks" $jc.blocks $cc.blocks
Same "bestblockhash" $jc.bestblockhash $cc.bestblockhash
Same "chainwork" $jc.chainwork $cc.chainwork

$height = [int]$jc.blocks
$checkpoints = @(0)
if ($height -gt 0) { $checkpoints += [Math]::Max(0, $height - 1); $checkpoints += $height }
$checkpoints = $checkpoints | Sort-Object -Unique
foreach ($h in $checkpoints) {
    $jh = Rpc $JavaRpc $JavaUser $JavaPassword "getblockhash" @($h)
    $ch = Rpc $CoreRpc $CoreUser $CorePassword "getblockhash" @($h)
    Same "blockhash[$h]" $jh $ch
}

# UTXO commitment is the strongest cheap state checkpoint exposed by both nodes.
$ju = Rpc $JavaRpc $JavaUser $JavaPassword "gettxoutsetinfo" @("hash_serialized_3")
$cu = Rpc $CoreRpc $CoreUser $CorePassword "gettxoutsetinfo" @("hash_serialized_3")
Same "utxo.height" $ju.height $cu.height
Same "utxo.bestblock" $ju.bestblock $cu.bestblock
Same "utxo.txouts" $ju.txouts $cu.txouts
Same "utxo.total_amount" $ju.total_amount $cu.total_amount
Same "utxo.hash_serialized_3" $ju.hash_serialized_3 $cu.hash_serialized_3

Write-Host "CONSENSUS DIFFERENTIAL CHECK PASSED" -ForegroundColor Green
