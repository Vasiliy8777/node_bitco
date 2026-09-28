param(
 [Parameter(Mandatory=$true)][string]$JavaRpc,
 [Parameter(Mandatory=$true)][string]$CoreRpc,
 [string]$JavaUser="bitcoin", [string]$JavaPassword="bitcoin",
 [string]$CoreUser="bitcoin", [string]$CorePassword="bitcoin",
 [string]$JavaP2P="127.0.0.1:18455"
)
Set-StrictMode -Version Latest
$ErrorActionPreference="Stop"
$root=(Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Push-Location $root
try {
 & .\mvnw.cmd clean test
 if($LASTEXITCODE -ne 0){throw "Maven test gate failed"}
 $vectorDir=(Join-Path $root "target\bip324-vectors")
 & (Join-Path $PSScriptRoot "fetch-bip324-vectors.ps1") -Destination $vectorDir | Out-Host
 & .\mvnw.cmd -pl crypto "-Dtest=Bip324OfficialVectorsTest" "-Dbip324.vectors.dir=$vectorDir" test
 if($LASTEXITCODE -ne 0){throw "Official BIP324 vector gate failed"}
 & (Join-Path $PSScriptRoot "bip324-core31-certify.ps1") -JavaRpc $JavaRpc -CoreRpc $CoreRpc -JavaUser $JavaUser -JavaPassword $JavaPassword -CoreUser $CoreUser -CorePassword $CorePassword -JavaP2P $JavaP2P
 Write-Host "BIP324 / CORE INTEROP / P2P HARDENING STAGE: CLOSED"
} finally { Pop-Location }
