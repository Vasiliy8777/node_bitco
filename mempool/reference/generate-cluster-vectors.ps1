$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path $PSScriptRoot -Parent
$buildDirectory = Join-Path $moduleRoot 'target/core32-cluster-reference'
$includeDirectory = Join-Path $buildDirectory 'include'
New-Item -ItemType Directory -Force -Path (Join-Path $includeDirectory 'util') | Out-Null
$sources = @('cluster_linearize.h', 'util/feefrac.h', 'util/bitset.h', 'util/overflow.h', 'util/vecdeque.h', 'span.h')
$hashes = [Collections.Generic.List[string]]::new()
foreach ($source in $sources) {
    $path = Join-Path $includeDirectory $source
    Invoke-WebRequest ('https://raw.githubusercontent.com/bitcoin/bitcoin/v32.1/src/' + $source) -OutFile $path
    $hashes.Add("# $source SHA256 $((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant())")
}
# Platform/assertion/allocation/RNG shims only; graph, optimization, minimization,
# ordering, fee arithmetic and chunking all execute unchanged Core headers.
[IO.File]::WriteAllText((Join-Path $includeDirectory 'attributes.h'), "#pragma once`n#define LIFETIMEBOUND`n")
[IO.File]::WriteAllText((Join-Path $includeDirectory 'util/check.h'), "#pragma once`n#include <cassert>`n#define Assume(e) assert(e)`n#define Assert(e) assert(e)`n")
[IO.File]::WriteAllText((Join-Path $includeDirectory 'memusage.h'), "#pragma once`n#include <cstddef>`nnamespace memusage { template<class T> std::size_t DynamicUsage(const T&) { return 0; } }`n")
$randomHeader = @'
#pragma once
#include <random>
#include <cstdint>
class InsecureRandomContext {
    std::mt19937_64 engine;
public:
    explicit InsecureRandomContext(uint64_t seed) : engine(seed) {}
    uint64_t rand64() { return engine(); }
    bool randbool() { return (rand64() & 1) != 0; }
    template<int BITS> uint64_t randbits() { return rand64() >> (64 - BITS); }
    template<class T> T randrange(T range) { return std::uniform_int_distribution<uint64_t>(0, range - 1)(engine); }
};
'@
[IO.File]::WriteAllText((Join-Path $includeDirectory 'random.h'), $randomHeader)
$vswhere = "${env:ProgramFiles(x86)}/Microsoft Visual Studio/Installer/vswhere.exe"
$installation = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $installation) { throw 'MSVC x64 is required only to regenerate fixtures' }
$vcvars = Join-Path $installation 'VC/Auxiliary/Build/vcvars64.bat'
$oracleSource = Join-Path $PSScriptRoot 'cluster-reference.cpp'
$batch = "@echo off`r`ncall `"$vcvars`" >nul`r`nif errorlevel 1 exit /b 1`r`ncl /nologo /O2 /EHsc /std:c++latest /I`"$includeDirectory`" `"$oracleSource`" /Fe:cluster-reference.exe > compile.log 2>&1`r`nexit /b %errorlevel%`r`n"
$batchPath = Join-Path $buildDirectory 'build-reference.cmd'
[IO.File]::WriteAllText($batchPath, $batch)
Push-Location $buildDirectory
try {
    & $env:ComSpec /d /c $batchPath
    if ($LASTEXITCODE -ne 0) { Get-Content compile.log -Tail 30; throw 'Core cluster oracle build failed' }
} finally { Pop-Location }

$random = [Random]::new(320101)
$oracleInput = [Text.StringBuilder]::new()
$rows = [Collections.Generic.List[string]]::new()
for ($caseIndex = 0; $caseIndex -lt 384; $caseIndex++) {
    $count = if ($caseIndex -lt 65) { $caseIndex } else { $random.Next(2, 65) }
    [void]$oracleInput.AppendLine("$caseIndex $count")
    $encoded = [Collections.Generic.List[string]]::new()
    for ($i = 0; $i -lt $count; $i++) {
        $fee = switch ($caseIndex % 8) { 0 { 0 }; 1 { 1000 }; 2 { $random.Next(-100000, 100001) }; 3 { [long]$random.Next(1000000, 10000000) * 100000000 }; default { $random.Next(0, 100001) } }
        $weight = if ($caseIndex % 8 -eq 1) { 400 } else { $random.Next(100, 6001) }
        $parents = [Collections.Generic.List[int]]::new()
        for ($p = 0; $p -lt $i; $p++) {
            $connect = switch ($caseIndex % 6) { 0 { $p -eq $i - 1 }; 1 { $p -eq 0 }; 2 { $random.Next(3) -eq 0 }; default { $random.Next(10) -eq 0 } }
            if ($connect) { $parents.Add($p) }
        }
        $parentList = $parents -join ','
        $encoded.Add("${fee},${weight}:$parentList")
        [void]$oracleInput.AppendLine("$fee $weight $($parents.Count) $($parents -join ' ')")
    }
    $rows.Add("$caseIndex`t$($encoded -join ';')")
}
$inputPath = Join-Path $buildDirectory 'input.txt'
$outputPath = Join-Path $buildDirectory 'output.txt'
[IO.File]::WriteAllText($inputPath, $oracleInput.ToString())
$oracle = Join-Path $buildDirectory 'cluster-reference.exe'
$process = Start-Process -FilePath $oracle -RedirectStandardInput $inputPath -RedirectStandardOutput $outputPath -WindowStyle Hidden -Wait -PassThru
if ($process.ExitCode -ne 0) { throw "Core cluster oracle failed: $($process.ExitCode)" }
$answers = Get-Content -LiteralPath $outputPath
if ($answers.Count -ne $rows.Count) { throw 'Core oracle returned wrong case count' }
$fixtures = [Collections.Generic.List[string]]::new()
$fixtures.Add('# Bitcoin Core v32.1 optimal cluster linearization; RNG shim, cost budget 1000000000, optimal required')
$fixtures.AddRange($hashes)
$fixtures.Add('# case<TAB>fee,weight:parents;...<TAB>order<TAB>chunk fee,weight:members;...')
for ($index = 0; $index -lt $rows.Count; $index++) {
    $answer = $answers[$index].Split("`t", 3)
    if ($rows[$index].Split("`t", 2)[0] -ne $answer[0]) { throw 'Oracle case mismatch' }
    $fixtures.Add("$($rows[$index])`t$($answer[1])`t$($answer[2])")
}
$resources = Join-Path $moduleRoot 'src/test/resources/core32-reference'
New-Item -ItemType Directory -Force -Path $resources | Out-Null
[IO.File]::WriteAllLines((Join-Path $resources 'cluster-vectors.tsv'), $fixtures)
Write-Output "Generated $($rows.Count) independently optimized Core cluster cases"
