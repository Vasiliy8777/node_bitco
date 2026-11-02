$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path $PSScriptRoot -Parent
$buildDirectory = Join-Path $moduleRoot 'target/core32-eviction-reference'
$includeDirectory = Join-Path $buildDirectory 'include/node'
New-Item -ItemType Directory -Force -Path $includeDirectory | Out-Null
$sourceUrl = 'https://raw.githubusercontent.com/bitcoin/bitcoin/v32.1/src/node/eviction.cpp'
Invoke-WebRequest $sourceUrl -OutFile (Join-Path $buildDirectory 'eviction.cpp')
$sourceHash = (Get-FileHash -LiteralPath (Join-Path $buildDirectory 'eviction.cpp') -Algorithm SHA256).Hash.ToLowerInvariant()
# Only declarations/dependency types are stubbed; all selection code is unchanged Core source.
$declarations = @'
#pragma once
#include <chrono>
#include <cstdint>
#include <optional>
#include <vector>
#include <cassert>
struct NodeClock : std::chrono::system_clock { static constexpr time_point epoch{}; };
enum Network { NET_IPV4, NET_IPV6, NET_ONION, NET_I2P, NET_CJDNS, NET_UNROUTABLE, NET_MAX };
enum class ConnectionType { INBOUND, FULL_RELAY };
using NodeId = int64_t;
struct NodeEvictionCandidate {
    NodeId id; NodeClock::time_point m_connected; NodeClock::duration m_min_ping_time;
    std::chrono::seconds m_last_block_time; std::chrono::seconds m_last_tx_time;
    bool fRelevantServices; bool m_relay_txs; bool fBloomFilter; uint64_t nKeyedNetGroup;
    bool prefer_evict; bool m_is_local; Network m_network; bool m_noban; ConnectionType m_conn_type;
};
std::optional<NodeId> SelectNodeToEvict(std::vector<NodeEvictionCandidate>&&);
void ProtectEvictionCandidatesByRatio(std::vector<NodeEvictionCandidate>&);
'@
[IO.File]::WriteAllText((Join-Path $includeDirectory 'eviction.h'), $declarations)
$vswhere = "${env:ProgramFiles(x86)}/Microsoft Visual Studio/Installer/vswhere.exe"
$installation = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $installation) { throw 'MSVC x64 is required only to regenerate fixtures' }
$vcvars = Join-Path $installation 'VC/Auxiliary/Build/vcvars64.bat'
$oracleSource = Join-Path $PSScriptRoot 'eviction-reference.cpp'
$includeRoot = Join-Path $buildDirectory 'include'
$batch = "@echo off`r`ncall `"$vcvars`" >nul`r`nif errorlevel 1 exit /b 1`r`ncl /nologo /O2 /EHsc /std:c++20 /I`"$includeRoot`" /I. `"$oracleSource`" /Fe:eviction-reference.exe > compile.log 2>&1`r`nexit /b %errorlevel%`r`n"
$batchPath = Join-Path $buildDirectory 'build-reference.cmd'
[IO.File]::WriteAllText($batchPath, $batch)
Push-Location $buildDirectory
try {
    & $env:ComSpec /d /c $batchPath
    if ($LASTEXITCODE -ne 0) { Get-Content compile.log -Tail 25; throw 'Core eviction oracle build failed' }
} finally { Pop-Location }

$random = [Random]::new(320100)
function New-Permutation([int]$length) {
    $values = [int[]]::new($length)
    for ($index = 0; $index -lt $length; $index++) { $values[$index] = $index }
    for ($index = $length - 1; $index -gt 0; $index--) {
        $other = $random.Next($index + 1)
        $swap = $values[$index]; $values[$index] = $values[$other]; $values[$other] = $swap
    }
    return ,$values
}
$oracleInput = [Text.StringBuilder]::new()
$rows = [Collections.Generic.List[string]]::new()
for ($caseIndex = 0; $caseIndex -lt 192; $caseIndex++) {
    $mode = $caseIndex % 2
    $count = if ($caseIndex -lt 32) { $caseIndex } else { $random.Next(32, 201) }
    $pingOrder = New-Permutation $count
    $txOrder = New-Permutation $count
    $blockOrder = New-Permutation $count
    $inputOrder = New-Permutation $count
    $candidateRows = [Collections.Generic.List[string]]::new()
    [void]$oracleInput.AppendLine("$caseIndex $mode $count")
    foreach ($index in $inputOrder) {
        [UInt64]$group = [UInt64]$random.Next(1, 1000000) * 256 + [UInt64]$index
        if ($caseIndex % 3 -eq 0 -and $index -lt $count - 8) { $group = 0 }
        elseif ($caseIndex % 3 -eq 1) { $group = [UInt64]::MaxValue - $group }
        $candidate = @(
            $index, ($index + 1), (($pingOrder[$index] + 1) * 1000000L),
            (($blockOrder[$index] + 1) * 17), (($txOrder[$index] + 1) * 13),
            $random.Next(2), $random.Next(2), $random.Next(2), $group,
            [int]($random.Next(5) -eq 0), [int]($random.Next(5) -eq 0), $random.Next(6),
            [int]($random.Next(13) -eq 0), [int]($random.Next(7) -ne 0))
        $encoded = $candidate -join ','
        $candidateRows.Add($encoded)
        [void]$oracleInput.AppendLine(($candidate -join ' '))
    }
    $rows.Add("$caseIndex`t$mode`t" + ($candidateRows -join ';'))
}
$inputPath = Join-Path $buildDirectory 'input.txt'
$outputPath = Join-Path $buildDirectory 'output.txt'
[IO.File]::WriteAllText($inputPath, $oracleInput.ToString())
Get-Content -LiteralPath $inputPath | & (Join-Path $buildDirectory 'eviction-reference.exe') | Set-Content -Encoding ascii -LiteralPath $outputPath
if ($LASTEXITCODE -ne 0) { throw 'Core eviction oracle failed' }
$answers = Get-Content -LiteralPath $outputPath
if ($answers.Count -ne $rows.Count) { throw 'Core oracle returned wrong case count' }
$fixtures = [Collections.Generic.List[string]]::new()
$fixtures.Add("# Bitcoin Core v32.1 node/eviction.cpp SHA256 $sourceHash")
$fixtures.Add('# case<TAB>mode(0=select,1=ratio)<TAB>expected_ids<TAB>14-field candidates separated by semicolons')
for ($index = 0; $index -lt $rows.Count; $index++) {
    $row = $rows[$index].Split("`t", 3)
    $answer = $answers[$index].Split("`t", 2)
    if ($row[0] -ne $answer[0]) { throw 'Oracle case mismatch' }
    $fixtures.Add("$($row[0])`t$($row[1])`t$($answer[1])`t$($row[2])")
}
$resources = Join-Path $moduleRoot 'src/test/resources/core32-reference'
New-Item -ItemType Directory -Force -Path $resources | Out-Null
[IO.File]::WriteAllLines((Join-Path $resources 'eviction-vectors.tsv'), $fixtures)
Write-Output "Generated $($rows.Count) independent Core eviction cases; source SHA256 $sourceHash"
