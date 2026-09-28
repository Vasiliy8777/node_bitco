param([string]$Destination = (Join-Path $PSScriptRoot "..\target\bip324-vectors"))
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
$base = "https://raw.githubusercontent.com/bitcoin/bips/master/bip-0324"
$files = @("ellswift_decode_test_vectors.csv", "xswiftec_inv_test_vectors.csv", "packet_encoding_test_vectors.csv")
foreach ($name in $files) {
    $path = Join-Path $Destination $name
    Invoke-WebRequest -UseBasicParsing -Uri "$base/$name" -OutFile $path
    if ((Get-Item $path).Length -le 0) { throw "Downloaded empty BIP324 vector file: $name" }
    Write-Host "[OK] downloaded $name"
}
$decodeLines = (Get-Content (Join-Path $Destination $files[0])).Count
$inverseLines = (Get-Content (Join-Path $Destination $files[1])).Count
$packetLines = (Get-Content (Join-Path $Destination $files[2])).Count
if ($decodeLines -lt 70 -or $inverseLines -lt 30 -or $packetLines -lt 7) { throw "BIP324 corpus is unexpectedly short" }
Write-Host "[OK] official BIP324 corpus: decode=$($decodeLines-1), inverse=$($inverseLines-1), packet=$($packetLines-1)"
Resolve-Path $Destination
