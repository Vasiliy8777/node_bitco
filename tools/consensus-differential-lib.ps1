Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-BitcoinRpc {
    param([string]$Url,[string]$User,[string]$Password,[string]$Method,[object[]]$Params=@(),[switch]$AllowError)
    $body = @{jsonrpc="2.0"; id="consensus-certification"; method=$Method; params=$Params} | ConvertTo-Json -Depth 40 -Compress
    $pair = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("$User`:$Password"))
    try {
        $r = Invoke-RestMethod -Uri $Url -Method Post -Headers @{Authorization="Basic $pair"} -ContentType "application/json" -Body $body

        # Under Set-StrictMode, direct access to a missing property (for example
        # $r.error on a successful response that omits "error") throws.  Inspect
        # the PSObject property bag first so both Bitcoin Core-style responses
        # (result + error) and compact responses (result only) are accepted.
        $errorProperty = $r.PSObject.Properties['error']
        $rpcError = if ($null -ne $errorProperty) { $errorProperty.Value } else { $null }
        if ($null -ne $rpcError) {
            if ($AllowError) { return [pscustomobject]@{Ok=$false; Result=$null; Error=$rpcError} }
            throw "$Method failed at $Url : $($rpcError | ConvertTo-Json -Depth 20 -Compress)"
        }

        $resultProperty = $r.PSObject.Properties['result']
        if ($null -eq $resultProperty) {
            $responseJson = $r | ConvertTo-Json -Depth 20 -Compress
            $message = "$Method returned a JSON-RPC response without a result property at $Url : $responseJson"
            if ($AllowError) { return [pscustomobject]@{Ok=$false; Result=$null; Error=$message} }
            throw $message
        }

        $rpcResult = $resultProperty.Value
        if ($AllowError) { return [pscustomobject]@{Ok=$true; Result=$rpcResult; Error=$null} }
        return $rpcResult
    } catch {
        if ($AllowError) { return [pscustomobject]@{Ok=$false; Result=$null; Error=$_.Exception.Message} }
        throw
    }
}

function Assert-Same {
    param([string]$Name,$Java,$Core)
    $a=$Java | ConvertTo-Json -Depth 50 -Compress
    $b=$Core | ConvertTo-Json -Depth 50 -Compress
    if ($a -ne $b) { throw "CONSENSUS DIVERGENCE [$Name]`nJAVA: $a`nCORE: $b" }
    Write-Host "[OK] $Name"
}

function Get-NodeState {
    param([string]$Url,[string]$User,[string]$Password)
    $bc=Invoke-BitcoinRpc $Url $User $Password "getblockchaininfo"
    $utxo=Invoke-BitcoinRpc $Url $User $Password "gettxoutsetinfo" @("hash_serialized_3")
    [ordered]@{chain=$bc.chain; blocks=$bc.blocks; bestblockhash=$bc.bestblockhash; chainwork=$bc.chainwork;
        utxo_height=$utxo.height; utxo_bestblock=$utxo.bestblock; txouts=$utxo.txouts;
        total_amount=$utxo.total_amount; hash_serialized_3=$utxo.hash_serialized_3}
}

function Assert-StateEqual {
    param($Java,$Core,[string]$Prefix="state")
    foreach($k in @("chain","blocks","bestblockhash","chainwork","utxo_height","utxo_bestblock","txouts","total_amount","hash_serialized_3")) {
        Assert-Same "$Prefix.$k" $Java[$k] $Core[$k]
    }
}

function Convert-HexToBytes([string]$Hex) { return [Convert]::FromHexString($Hex) }
function Convert-BytesToHex([byte[]]$Bytes) { return [Convert]::ToHexString($Bytes).ToLowerInvariant() }
