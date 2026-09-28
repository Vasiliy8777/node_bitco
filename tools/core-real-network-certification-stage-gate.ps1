param(
 [ValidateSet("Core31","RealNetwork","All")][string]$Mode="All",
 [string]$RegtestJavaRpc,[string]$RegtestCoreRpc,[string]$CoreBinary,
 [string]$RegtestJavaUser="bitcoin",[string]$RegtestJavaPassword="bitcoin",
 [string]$RegtestCoreUser="bitcoin",[string]$RegtestCorePassword="bitcoin",
 [string]$RegtestJavaP2P="127.0.0.1:18455",
 [string]$RealJavaRpc,[string]$RealCoreRpc,
 [string]$RealJavaUser="bitcoin",[string]$RealJavaPassword="bitcoin",
 [string]$RealCoreUser="bitcoin",[string]$RealCorePassword="bitcoin",
 [ValidateSet("main","test","testnet4","signet")][string]$RealChain="test",
 [int]$WaitSeconds=600,[int]$MutationCount=64,[int]$ReorgDepth=3
)
Set-StrictMode -Version Latest
$ErrorActionPreference="Stop"
if($Mode -in @("Core31","All")){
 foreach($v in @("RegtestJavaRpc","RegtestCoreRpc","CoreBinary")){if([string]::IsNullOrWhiteSpace((Get-Variable $v -ValueOnly))){throw "$v is required for $Mode"}}
 & (Join-Path $PSScriptRoot "core31-full-certify.ps1") -JavaRpc $RegtestJavaRpc -CoreRpc $RegtestCoreRpc -CoreBinary $CoreBinary -JavaUser $RegtestJavaUser -JavaPassword $RegtestJavaPassword -CoreUser $RegtestCoreUser -CorePassword $RegtestCorePassword -JavaP2P $RegtestJavaP2P -MutationCount $MutationCount -ReorgDepth $ReorgDepth
 if($LASTEXITCODE -ne 0){throw "Core31 certification failed: $LASTEXITCODE"}
}
if($Mode -in @("RealNetwork","All")){
 foreach($v in @("RealJavaRpc","RealCoreRpc")){if([string]::IsNullOrWhiteSpace((Get-Variable $v -ValueOnly))){throw "$v is required for $Mode"}}
 & (Join-Path $PSScriptRoot "real-network-core-differential.ps1") -JavaRpc $RealJavaRpc -CoreRpc $RealCoreRpc -JavaUser $RealJavaUser -JavaPassword $RealJavaPassword -CoreUser $RealCoreUser -CorePassword $RealCorePassword -ExpectedChain $RealChain -WaitSeconds $WaitSeconds
 if($LASTEXITCODE -ne 0){throw "Real-network certification failed: $LASTEXITCODE"}
}
if($Mode -eq "All"){
 Write-Host "BITCOIN CORE DIFFERENTIAL + REAL-NETWORK CERTIFICATION STAGE: CLOSED" -ForegroundColor Green
}else{
 Write-Host "CERTIFICATION SUBSTAGE ${Mode}: PASSED" -ForegroundColor Green
}
