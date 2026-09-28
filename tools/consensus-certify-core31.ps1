param(
    [Parameter(Mandatory = $true)]
    [string]$JavaRpc,

    [Parameter(Mandatory = $true)]
    [string]$CoreRpc,

    [string]$JavaUser = "bitcoin",
    [string]$JavaPassword = "bitcoin",

    [string]$CoreUser = "bitcoin",
    [string]$CorePassword = "bitcoin",

    [ValidateRange(1, 256)]
    [int]$MutationCount = 64,

    [ValidateRange(1, 32)]
    [int]$ReorgDepth = 3
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptDir "consensus-differential-lib.ps1")


function Assert-Core31 {
    param(
        [string]$Url,
        [string]$User,
        [string]$Password
    )

    $networkInfo = Invoke-BitcoinRpc `
        -Url $Url `
        -User $User `
        -Password $Password `
        -Method "getnetworkinfo"

    $version = [long]$networkInfo.version

    if ($version -lt 310000 -or $version -ge 320000) {
        throw "Bitcoin Core 31.x is required. Reported version: $version"
    }

    Write-Host "[OK] Bitcoin Core reference: $($networkInfo.subversion)"
}


function Test-SubmitRejected {
    param(
        [string]$Label,
        [string]$BlockHex
    )

    $javaResponse = Invoke-BitcoinRpc `
        -Url $JavaRpc `
        -User $JavaUser `
        -Password $JavaPassword `
        -Method "submitblock" `
        -Params @($BlockHex) `
        -AllowError

    $coreResponse = Invoke-BitcoinRpc `
        -Url $CoreRpc `
        -User $CoreUser `
        -Password $CorePassword `
        -Method "submitblock" `
        -Params @($BlockHex) `
        -AllowError

    $javaRejected = (-not $javaResponse.Ok) -or ($null -ne $javaResponse.Result)
    $coreRejected = (-not $coreResponse.Ok) -or ($null -ne $coreResponse.Result)

    if (-not $javaRejected) {
        throw "${Label}: Java node unexpectedly accepted malformed block"
    }

    if (-not $coreRejected) {
        throw "${Label}: Bitcoin Core unexpectedly accepted malformed block"
    }

    Write-Host "[OK] $Label rejected by both nodes"
}


function Convert-HexToBytes {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Hex
    )

    if (($Hex.Length % 2) -ne 0) {
        throw "Invalid hex string length: $($Hex.Length)"
    }

    $bytes = New-Object byte[] ($Hex.Length / 2)

    for ($i = 0; $i -lt $bytes.Length; $i++) {
        $bytes[$i] = [Convert]::ToByte(
            $Hex.Substring($i * 2, 2),
            16
        )
    }

    return $bytes
}


function Convert-BytesToHex {
    param(
        [Parameter(Mandatory = $true)]
        [byte[]]$Bytes
    )

    return ([BitConverter]::ToString($Bytes)).Replace("-", "").ToLowerInvariant()
}


function Compare-BlockAtHeight {
    param(
        [Parameter(Mandatory = $true)]
        [long]$Height
    )

    $javaHash = Invoke-BitcoinRpc `
        -Url $JavaRpc `
        -User $JavaUser `
        -Password $JavaPassword `
        -Method "getblockhash" `
        -Params @($Height)

    $coreHash = Invoke-BitcoinRpc `
        -Url $CoreRpc `
        -User $CoreUser `
        -Password $CorePassword `
        -Method "getblockhash" `
        -Params @($Height)

    Assert-Same `
        -Name "height[$Height].hash" `
        -Java $javaHash `
        -Core $coreHash

    $javaHeader = Invoke-BitcoinRpc `
        -Url $JavaRpc `
        -User $JavaUser `
        -Password $JavaPassword `
        -Method "getblockheader" `
        -Params @($javaHash, $false)

    $coreHeader = Invoke-BitcoinRpc `
        -Url $CoreRpc `
        -User $CoreUser `
        -Password $CorePassword `
        -Method "getblockheader" `
        -Params @($coreHash, $false)

    Assert-Same `
        -Name "height[$Height].header" `
        -Java $javaHeader `
        -Core $coreHeader

    $javaBlock = Invoke-BitcoinRpc `
        -Url $JavaRpc `
        -User $JavaUser `
        -Password $JavaPassword `
        -Method "getblock" `
        -Params @($javaHash, 0)

    $coreBlock = Invoke-BitcoinRpc `
        -Url $CoreRpc `
        -User $CoreUser `
        -Password $CorePassword `
        -Method "getblock" `
        -Params @($coreHash, 0)

    Assert-Same `
        -Name "height[$Height].block" `
        -Java $javaBlock `
        -Core $coreBlock
}


Assert-Core31 `
    -Url $CoreRpc `
    -User $CoreUser `
    -Password $CorePassword


$javaInitial = Get-NodeState `
    -Url $JavaRpc `
    -User $JavaUser `
    -Password $JavaPassword

$coreInitial = Get-NodeState `
    -Url $CoreRpc `
    -User $CoreUser `
    -Password $CorePassword

Assert-StateEqual `
    -Prefix "initial" `
    -Java $javaInitial `
    -Core $coreInitial


$height = [long]$coreInitial.blocks

if ($height -lt ([long]$ReorgDepth + 1L)) {
    throw "Chain height $height is too small for reorg depth $ReorgDepth"
}


#
# Explicit calculations are intentional.
#
# PowerShell can interpret arithmetic expressions embedded directly inside
# array expressions in surprising ways under StrictMode. Keeping each height
# scalar avoids System.Object[] / op_Subtraction errors.
#

$midHeight = [long][Math]::Floor(
    [double]$height / 2.0
)

$previousHeight = [long]($height - 1L)

$heights = @(
    [long]0
    [long]$midHeight
    [long]$previousHeight
    [long]$height
) | Sort-Object -Unique


foreach ($checkHeight in $heights) {
    Compare-BlockAtHeight -Height ([long]$checkHeight)
}


#
# Deterministic malformed-block differential tests.
#

$coreTipHash = Invoke-BitcoinRpc `
    -Url $CoreRpc `
    -User $CoreUser `
    -Password $CorePassword `
    -Method "getblockhash" `
    -Params @($height)

$coreTipBlockHex = Invoke-BitcoinRpc `
    -Url $CoreRpc `
    -User $CoreUser `
    -Password $CorePassword `
    -Method "getblock" `
    -Params @($coreTipHash, 0)

$originalBytes = Convert-HexToBytes -Hex $coreTipBlockHex

if ($originalBytes.Length -lt 80) {
    throw "Reference block is shorter than an 80-byte Bitcoin block header"
}


for ($mutation = 0; $mutation -lt $MutationCount; $mutation++) {

    $mutated = New-Object byte[] $originalBytes.Length
    [Array]::Copy(
        $originalBytes,
        $mutated,
        $originalBytes.Length
    )

    #
    # Bytes 36..67 are the merkle root inside the block header.
    # Mutating these bytes deterministically invalidates the block while
    # preserving the serialized block structure.
    #

    $position = 36 + ($mutation % 32)
    $bit = 1 -shl ($mutation % 8)

    $mutated[$position] = [byte](
        $mutated[$position] -bxor $bit
    )

    $mutatedHex = Convert-BytesToHex -Bytes $mutated

    Test-SubmitRejected `
        -Label "mutation[$mutation]" `
        -BlockHex $mutatedHex
}


#
# Contextual rejection check: modify the previous-block hash area.
#

$unknownParentBytes = New-Object byte[] $originalBytes.Length

[Array]::Copy(
    $originalBytes,
    $unknownParentBytes,
    $originalBytes.Length
)

$unknownParentBytes[4] = [byte](
    $unknownParentBytes[4] -bxor 0x01
)

$unknownParentHex = Convert-BytesToHex `
    -Bytes $unknownParentBytes

Test-SubmitRejected `
    -Label "unknown-parent" `
    -BlockHex $unknownParentHex


#
# Reorg/disconnect/reconsider differential test.
#

$cutHeight = [long](
    $height - [long]$ReorgDepth + 1L
)

$cutHash = Invoke-BitcoinRpc `
    -Url $CoreRpc `
    -User $CoreUser `
    -Password $CorePassword `
    -Method "getblockhash" `
    -Params @($cutHeight)

$reconsiderRequired = $false

try {

    Invoke-BitcoinRpc `
        -Url $JavaRpc `
        -User $JavaUser `
        -Password $JavaPassword `
        -Method "invalidateblock" `
        -Params @($cutHash) | Out-Null

    Invoke-BitcoinRpc `
        -Url $CoreRpc `
        -User $CoreUser `
        -Password $CorePassword `
        -Method "invalidateblock" `
        -Params @($cutHash) | Out-Null

    $reconsiderRequired = $true


    $javaDisconnected = Get-NodeState `
        -Url $JavaRpc `
        -User $JavaUser `
        -Password $JavaPassword

    $coreDisconnected = Get-NodeState `
        -Url $CoreRpc `
        -User $CoreUser `
        -Password $CorePassword

    Assert-StateEqual `
        -Prefix "after-invalidate" `
        -Java $javaDisconnected `
        -Core $coreDisconnected


    Invoke-BitcoinRpc `
        -Url $JavaRpc `
        -User $JavaUser `
        -Password $JavaPassword `
        -Method "reconsiderblock" `
        -Params @($cutHash) | Out-Null

    Invoke-BitcoinRpc `
        -Url $CoreRpc `
        -User $CoreUser `
        -Password $CorePassword `
        -Method "reconsiderblock" `
        -Params @($cutHash) | Out-Null

    $reconsiderRequired = $false


    $javaRestored = Get-NodeState `
        -Url $JavaRpc `
        -User $JavaUser `
        -Password $JavaPassword

    $coreRestored = Get-NodeState `
        -Url $CoreRpc `
        -User $CoreUser `
        -Password $CorePassword

    Assert-StateEqual `
        -Prefix "after-reconsider" `
        -Java $javaRestored `
        -Core $coreRestored


    Assert-StateEqual `
        -Prefix "restored-vs-initial" `
        -Java $javaRestored `
        -Core $javaInitial
}
finally {

    if ($reconsiderRequired) {

        Write-Warning "Certification interrupted during reorg test; attempting to restore both chains."

        try {
            Invoke-BitcoinRpc `
                -Url $JavaRpc `
                -User $JavaUser `
                -Password $JavaPassword `
                -Method "reconsiderblock" `
                -Params @($cutHash) `
                -AllowError | Out-Null
        }
        catch {
            Write-Warning "Unable to reconsider block on Java node: $($_.Exception.Message)"
        }

        try {
            Invoke-BitcoinRpc `
                -Url $CoreRpc `
                -User $CoreUser `
                -Password $CorePassword `
                -Method "reconsiderblock" `
                -Params @($cutHash) `
                -AllowError | Out-Null
        }
        catch {
            Write-Warning "Unable to reconsider block on Bitcoin Core: $($_.Exception.Message)"
        }
    }
}


Write-Host ""
Write-Host "CONSENSUS DIFFERENTIAL/TESTING LIVE CERTIFICATION PASSED"