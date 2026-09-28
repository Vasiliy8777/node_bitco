param(
 [Parameter(Mandatory=$true)][string]$JavaRpc,
 [Parameter(Mandatory=$true)][string]$CoreRpc,
 [string]$JavaUser="bitcoin", [string]$JavaPassword="bitcoin",
 [string]$CoreUser="bitcoin", [string]$CorePassword="bitcoin",
 [string]$JavaP2P="127.0.0.1:18455", [int]$WaitSeconds=10
)
Set-StrictMode -Version Latest
$ErrorActionPreference="Stop"
. (Join-Path $PSScriptRoot "consensus-differential-lib.ps1")
function Get-Property($o,[string]$n){ $p=$o.PSObject.Properties[$n]; if($null -eq $p){return $null}; return $p.Value }
$coreNet=Invoke-BitcoinRpc -Url $CoreRpc -User $CoreUser -Password $CorePassword -Method "getnetworkinfo"
if([long]$coreNet.version -lt 310000 -or [long]$coreNet.version -ge 320000){throw "Bitcoin Core 31.x required"}
$javaNet=Invoke-BitcoinRpc -Url $JavaRpc -User $JavaUser -Password $JavaPassword -Method "getnetworkinfo"
if(-not (@($javaNet.localservicesnames) -contains "P2P_V2")){throw "Java node does not advertise P2P_V2"}
Write-Host "[OK] both endpoints available; Core $($coreNet.subversion); Java advertises P2P_V2"

# Java -> Core: the configured initial peer must be genuinely v2, with a session id Core also reports.
$jp=@(Invoke-BitcoinRpc -Url $JavaRpc -User $JavaUser -Password $JavaPassword -Method "getpeerinfo")
$cp=@(Invoke-BitcoinRpc -Url $CoreRpc -User $CoreUser -Password $CorePassword -Method "getpeerinfo")
$jOut=@($jp | Where-Object { -not $_.inbound -and $_.transport_protocol_type -eq "v2" -and $_.session_id })
if($jOut.Count -eq 0){throw "No Java outbound v2 peer found"}
$matched=$false
foreach($j in $jOut){ if(@($cp | Where-Object {$_.transport_protocol_type -eq "v2" -and $_.session_id -eq $j.session_id}).Count -gt 0){$matched=$true;break} }
if(-not $matched){throw "Java->Core v2 session_id is not visible on both endpoints"}
Write-Host "[OK] Java -> Core BIP324 v2 session matched by session_id"

# Core -> Java, explicitly force v2 transport.
Invoke-BitcoinRpc -Url $CoreRpc -User $CoreUser -Password $CorePassword -Method "addnode" -Params @($JavaP2P,"onetry",$true) | Out-Null
$deadline=(Get-Date).AddSeconds($WaitSeconds); $inbound=$null
while((Get-Date) -lt $deadline){
 Start-Sleep -Milliseconds 250
 $jp=@(Invoke-BitcoinRpc -Url $JavaRpc -User $JavaUser -Password $JavaPassword -Method "getpeerinfo")
 $candidates=@($jp | Where-Object {$_.inbound -and $_.transport_protocol_type -eq "v2" -and $_.session_id})
 if($candidates.Count -gt 0){$inbound=$candidates[0]}
 if($null -ne $inbound){break}
}
if($null -eq $inbound){throw "Core -> Java forced v2 connection did not become READY"}
$cp=@(Invoke-BitcoinRpc -Url $CoreRpc -User $CoreUser -Password $CorePassword -Method "getpeerinfo")
$coreMatch=@($cp | Where-Object {$_.transport_protocol_type -eq "v2" -and $_.session_id -eq $inbound.session_id})
if($coreMatch.Count -eq 0){throw "Core->Java v2 session_id is not visible on both endpoints"}
Write-Host "[OK] Core -> Java BIP324 v2 session matched by session_id"
Write-Host "BIP324 CORE 31 INTEROPERABILITY CERTIFICATION PASSED"
