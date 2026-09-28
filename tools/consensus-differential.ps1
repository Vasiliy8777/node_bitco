param(
    [Parameter(Mandatory=$true)][string]$JavaRpc,
    [Parameter(Mandatory=$true)][string]$CoreRpc,
    [string]$JavaUser="bitcoin", [string]$JavaPassword="bitcoin",
    [string]$CoreUser="bitcoin", [string]$CorePassword="bitcoin",
    [int]$MutationCount=64, [int]$ReorgDepth=3
)
& (Join-Path $PSScriptRoot "consensus-certify-core31.ps1") @PSBoundParameters
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
