param(
 [Parameter(Mandatory=$true)][string]$JavaRpc,
 [Parameter(Mandatory=$true)][string]$CoreRpc,
 [Parameter(Mandatory=$true)][string]$CoreBinary,
 [string]$JavaUser="bitcoin",[string]$JavaPassword="bitcoin",
 [string]$CoreUser="bitcoin",[string]$CorePassword="bitcoin",
 [string]$JavaP2P="127.0.0.1:18455",
 [ValidateRange(1,256)][int]$MutationCount=64,[ValidateRange(1,32)][int]$ReorgDepth=3
)
Set-StrictMode -Version Latest
$ErrorActionPreference="Stop"
$root=Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
 Write-Host "[1/4] Full reactor"
 & ".\mvnw.cmd" clean test
 if($LASTEXITCODE -ne 0){throw "Full reactor failed: $LASTEXITCODE"}
 Write-Host "[2/4] Core 31 consensus differential"
 & (Join-Path $PSScriptRoot "consensus-certify-core31.ps1") -JavaRpc $JavaRpc -CoreRpc $CoreRpc -JavaUser $JavaUser -JavaPassword $JavaPassword -CoreUser $CoreUser -CorePassword $CorePassword -MutationCount $MutationCount -ReorgDepth $ReorgDepth
 if($LASTEXITCODE -ne 0){throw "Consensus differential failed: $LASTEXITCODE"}
 Write-Host "[3/4] Core 31 BIP324 bidirectional interoperability"
 & (Join-Path $PSScriptRoot "bip324-core31-certify.ps1") -JavaRpc $JavaRpc -CoreRpc $CoreRpc -JavaUser $JavaUser -JavaPassword $JavaPassword -CoreUser $CoreUser -CorePassword $CorePassword -JavaP2P $JavaP2P
 if($LASTEXITCODE -ne 0){throw "BIP324 certification failed: $LASTEXITCODE"}
 Write-Host "[4/4] Isolated Core mining + relay + reconnect + Stratum round-trip"
 & ".\mvnw.cmd" -pl app -am "-Dbitcoin.core.binary=$CoreBinary" -Dtest=BitcoinCoreMiningRoundTripTest "-Dsurefire.failIfNoSpecifiedTests=false" test
 if($LASTEXITCODE -ne 0){throw "Mining round-trip failed: $LASTEXITCODE"}
 Write-Host "BITCOIN CORE 31 FULL DIFFERENTIAL CERTIFICATION PASSED" -ForegroundColor Green
} finally { Pop-Location }
