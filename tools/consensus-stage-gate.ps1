param(
    [Parameter(Mandatory=$true)][string]$JavaRpc,
    [Parameter(Mandatory=$true)][string]$CoreRpc,
    [string]$JavaUser="bitcoin", [string]$JavaPassword="bitcoin",
    [string]$CoreUser="bitcoin", [string]$CorePassword="bitcoin",
    [int]$MutationCount=64, [int]$ReorgDepth=3
)
$ErrorActionPreference="Stop"
$root=Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    Write-Host "[1/2] Maven clean test"
    & ".\mvnw.cmd" clean test
    if ($LASTEXITCODE -ne 0) { throw "Maven clean test failed with exit code $LASTEXITCODE" }
    Write-Host "[2/2] Bitcoin Core 31 live consensus differential"
    & (Join-Path $PSScriptRoot "consensus-certify-core31.ps1") -JavaRpc $JavaRpc -CoreRpc $CoreRpc `
        -JavaUser $JavaUser -JavaPassword $JavaPassword -CoreUser $CoreUser -CorePassword $CorePassword `
        -MutationCount $MutationCount -ReorgDepth $ReorgDepth
    if ($LASTEXITCODE -ne 0) { throw "Live consensus certification failed with exit code $LASTEXITCODE" }
    Write-Host "CONSENSUS DIFFERENTIAL/TESTING STAGE: CLOSED" -ForegroundColor Green
} finally { Pop-Location }
