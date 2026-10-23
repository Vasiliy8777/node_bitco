$ErrorActionPreference = 'Stop'
$rvnCoreCommit = '6d48ae0175b10283248146ae3080e2ba70966739'
$rvnSourcePath = 'src/test/assets/asset_tests.cpp'
$rvnSource = (Invoke-WebRequest -Uri "https://raw.githubusercontent.com/RavenProject/Ravencoin/$rvnCoreCommit/$rvnSourcePath").Content
$rvnMatches = [regex]::Matches($rvnSource, 'BOOST_CHECK\((!)?IsAssetNameValid\("([^"]+)"(?:,\s*type)?\)\)')
if ($rvnMatches.Count -lt 50) { throw 'Unexpected Core asset name test corpus' }
$rvnLines = [System.Collections.Generic.List[string]]::new()
$rvnLines.Add("# Core $rvnCoreCommit $rvnSourcePath; literal name-validity assertions")
foreach ($rvnMatch in $rvnMatches) {
    $rvnExpected = if ($rvnMatch.Groups[1].Success) { 'false' } else { 'true' }
    $rvnLines.Add($rvnExpected + "`t" + $rvnMatch.Groups[2].Value)
}
$rvnModule = Split-Path $PSScriptRoot -Parent
$rvnOutput = Join-Path $rvnModule 'src/test/resources/core-reference/asset-names.tsv'
New-Item -ItemType Directory -Path (Split-Path $rvnOutput -Parent) -Force | Out-Null
[IO.File]::WriteAllLines($rvnOutput, $rvnLines, [Text.UTF8Encoding]::new($false))
Write-Output "Extracted $($rvnMatches.Count) asset name assertions from pinned Core tests"
