param(
    [Parameter(Mandatory=$true)][string]$JavaRpc,
    [Parameter(Mandatory=$true)][string]$CoreRpc,
    [string]$JavaUser="bitcoin", [string]$JavaPassword="bitcoin",
    [string]$CoreUser="bitcoin", [string]$CorePassword="bitcoin",
    [ValidateSet("main","test","testnet4","signet")][string]$ExpectedChain="test",
    [ValidateRange(30,7200)][int]$WaitSeconds=600
)
Set-StrictMode -Version Latest
$ErrorActionPreference="Stop"
. (Join-Path $PSScriptRoot "consensus-differential-lib.ps1")

function State([string]$url,[string]$user,[string]$password) {
    $chain=Invoke-BitcoinRpc -Url $url -User $user -Password $password -Method "getblockchaininfo"
    $net=Invoke-BitcoinRpc -Url $url -User $user -Password $password -Method "getnetworkinfo"
    $peers=@(Invoke-BitcoinRpc -Url $url -User $user -Password $password -Method "getpeerinfo")
    [pscustomobject]@{ chain=$chain; net=$net; peers=$peers }
}
function Assert-ReadyPeer($state,[string]$label) {
    if ([long]$state.net.connections -le 0 -or @($state.peers).Count -le 0) { throw "$label has no live peers" }
    $ready=@($state.peers | Where-Object { $_.state -eq "ready" -or $null -eq $_.PSObject.Properties["state"] })
    if($ready.Count -eq 0){ throw "$label has no READY peers" }
}
function Compare-Height([long]$height) {
    $jh=Invoke-BitcoinRpc -Url $JavaRpc -User $JavaUser -Password $JavaPassword -Method "getblockhash" -Params @($height)
    $ch=Invoke-BitcoinRpc -Url $CoreRpc -User $CoreUser -Password $CorePassword -Method "getblockhash" -Params @($height)
    Assert-Same -Name "real.height[$height].hash" -Java $jh -Core $ch
    $jhdr=Invoke-BitcoinRpc -Url $JavaRpc -User $JavaUser -Password $JavaPassword -Method "getblockheader" -Params @($jh,$false)
    $chdr=Invoke-BitcoinRpc -Url $CoreRpc -User $CoreUser -Password $CorePassword -Method "getblockheader" -Params @($ch,$false)
    Assert-Same -Name "real.height[$height].header" -Java $jhdr -Core $chdr
    $jb=Invoke-BitcoinRpc -Url $JavaRpc -User $JavaUser -Password $JavaPassword -Method "getblock" -Params @($jh,0)
    $cb=Invoke-BitcoinRpc -Url $CoreRpc -User $CoreUser -Password $CorePassword -Method "getblock" -Params @($ch,0)
    Assert-Same -Name "real.height[$height].block" -Java $jb -Core $cb
}

$deadline=(Get-Date).AddSeconds($WaitSeconds)
do {
    $j=State $JavaRpc $JavaUser $JavaPassword
    $c=State $CoreRpc $CoreUser $CorePassword
    if($j.chain.chain -ne $ExpectedChain){ throw "Java chain '$($j.chain.chain)' != expected '$ExpectedChain'" }
    if($c.chain.chain -ne $ExpectedChain){ throw "Core chain '$($c.chain.chain)' != expected '$ExpectedChain'" }
    $same=(-not [bool]$j.chain.initialblockdownload) -and (-not [bool]$c.chain.initialblockdownload) -and
          ([long]$j.chain.blocks -eq [long]$c.chain.blocks) -and ([long]$j.chain.headers -eq [long]$c.chain.headers) -and
          ([string]$j.chain.bestblockhash -eq [string]$c.chain.bestblockhash)
    if($same){ break }
    Start-Sleep -Seconds 2
} while((Get-Date) -lt $deadline)
if(-not $same){ throw "Public-network tips did not converge before timeout. Java=$($j.chain.blocks)/$($j.chain.headers) Core=$($c.chain.blocks)/$($c.chain.headers)" }
Assert-ReadyPeer $j "Java"
Assert-ReadyPeer $c "Core"
Assert-Same -Name "real.chain" -Java $j.chain.chain -Core $c.chain.chain
Assert-Same -Name "real.blocks" -Java $j.chain.blocks -Core $c.chain.blocks
Assert-Same -Name "real.headers" -Java $j.chain.headers -Core $c.chain.headers
Assert-Same -Name "real.bestblockhash" -Java $j.chain.bestblockhash -Core $c.chain.bestblockhash
Assert-Same -Name "real.chainwork" -Java $j.chain.chainwork -Core $c.chain.chainwork
$tip=[long]$c.chain.blocks
$heights=@([long]0,[long][Math]::Floor($tip/3.0),[long][Math]::Floor(2.0*$tip/3.0),$tip) | Sort-Object -Unique
foreach($h in $heights){ Compare-Height $h }
Write-Host "[OK] public-network peers live; both nodes out of IBD; chain/tip/chainwork/raw historical blocks match"
Write-Host "BITCOIN CORE REAL-NETWORK DIFFERENTIAL CERTIFICATION PASSED" -ForegroundColor Green
