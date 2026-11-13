$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path $PSScriptRoot -Parent
$workspaceRoot = Split-Path $moduleRoot -Parent
$directory = Join-Path $moduleRoot 'target/core32-budget-reference'
$include = Join-Path $directory 'include'
New-Item -ItemType Directory -Force -Path $directory | Out-Null
if (!(Test-Path -LiteralPath $include)) {
    Copy-Item -LiteralPath (Join-Path $moduleRoot 'target/core32-cluster-reference/include') -Destination $include -Recurse
}
$randomPath = Join-Path $workspaceRoot 'target/core-update-audit/core32-random-reference.h'
if (!(Test-Path -LiteralPath $randomPath)) { Invoke-WebRequest 'https://raw.githubusercontent.com/bitcoin/bitcoin/v32.1/src/random.h' -OutFile $randomPath }
$randomSource = Get-Content -LiteralPath $randomPath -Raw
$generator = [regex]::Match($randomSource, '(?s)class InsecureRandomContext :.*?\r?\n};').Value
if (!$generator) { throw 'Core RNG class missing' }
# Preserve the genuine Core xoroshiro class verbatim. The mixin below is the
# exact bit-buffer/range subset used by cluster_linearize.h; no RNG substitution.
$mixin = @'
#pragma once
#include <bit>
#include <cstdint>
#include <util/check.h>
template<typename T> class RandomMixin {
    uint64_t bitbuf{0}; int bitbuf_size{0};
protected:
    constexpr void FlushCache() noexcept { bitbuf = 0; bitbuf_size = 0; }
public:
    constexpr RandomMixin() noexcept = default;
    template<int Bits> uint64_t randbits() noexcept {
        if constexpr (Bits == 64) return static_cast<T*>(this)->rand64();
        else {
            uint64_t ret;
            if (Bits <= bitbuf_size) { ret = bitbuf; bitbuf >>= Bits; bitbuf_size -= Bits; }
            else {
                uint64_t gen = static_cast<T*>(this)->rand64();
                ret = (gen << bitbuf_size) | bitbuf;
                bitbuf = gen >> (Bits - bitbuf_size); bitbuf_size = 64 + bitbuf_size - Bits;
            }
            return ret & ((uint64_t{1} << Bits) - 1);
        }
    }
    uint64_t randbits(int bits) noexcept {
        if (bits == 64) return static_cast<T*>(this)->rand64();
        uint64_t ret;
        if (bits <= bitbuf_size) { ret = bitbuf; bitbuf >>= bits; bitbuf_size -= bits; }
        else {
            uint64_t gen = static_cast<T*>(this)->rand64(); ret = (gen << bitbuf_size) | bitbuf;
            bitbuf = gen >> (bits - bitbuf_size); bitbuf_size = 64 + bitbuf_size - bits;
        }
        return ret & ((uint64_t{1} << bits) - 1);
    }
    template<typename I> I randrange(I range) noexcept {
        Assume(range > 0); uint64_t maxval = range - 1U; int bits = std::bit_width(maxval);
        while (true) { uint64_t ret = randbits(bits); if (ret <= maxval) return ret; }
    }
    bool randbool() noexcept { return randbits<1>(); }
};
'@
[IO.File]::WriteAllText((Join-Path $include 'random.h'), $mixin + "`n" + $generator + "`n")
$vswhere = "${env:ProgramFiles(x86)}/Microsoft Visual Studio/Installer/vswhere.exe"
$installation = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (!$installation) { throw 'MSVC x64 is required to regenerate budget fixtures' }
$vcvars = Join-Path $installation 'VC/Auxiliary/Build/vcvars64.bat'
$source = Join-Path $PSScriptRoot 'budget-reference.cpp'
$batchPath = Join-Path $directory 'build-budget-reference.cmd'
$batch = "@echo off`r`ncall `"$vcvars`" >nul`r`nif errorlevel 1 exit /b 1`r`ncl /nologo /O2 /EHsc /std:c++latest /I`"$include`" `"$source`" /Fe:budget-reference.exe > compile.log 2>&1`r`nexit /b %errorlevel%`r`n"
[IO.File]::WriteAllText($batchPath, $batch)
Push-Location $directory
try {
    & $env:ComSpec /d /c $batchPath
    if ($LASTEXITCODE -ne 0) { Get-Content compile.log -Tail 30; throw 'Budget oracle compilation failed' }
} finally { Pop-Location }
$inputData = [Text.StringBuilder]::new()
$graphs = Get-Content (Join-Path $moduleRoot 'src/test/resources/core32-reference/cluster-vectors.tsv') | Where-Object { !($_.StartsWith('#')) }
foreach ($graph in $graphs) {
    $fields = $graph.Split("`t"); $caseIndex = [int]$fields[0]
    $vertices = if ($fields[1]) { @($fields[1].Split(';')) } else { @() }
    foreach ($budget in @(0, 1000, 75000, 375000, 1000000000)) {
        foreach ($mode in 0..4) {
            $seed = 320100 + $caseIndex * 17 + $mode
            [void]$inputData.AppendLine("$caseIndex $($vertices.Count) $budget $seed $mode")
            foreach ($vertex in $vertices) {
                $parts = $vertex.Split(':'); $rate = $parts[0].Split(',')
                $parents = if ($parts[1]) { @($parts[1].Split(',')) } else { @() }
                [void]$inputData.AppendLine("$($rate[0]) $($rate[1]) $($parents.Count) $($parents -join ' ')")
            }
        }
    }
}
$inputPath = Join-Path $directory 'input.txt'; $outputPath = Join-Path $directory 'output.txt'
[IO.File]::WriteAllText($inputPath, $inputData.ToString())
$process = Start-Process -FilePath (Join-Path $directory 'budget-reference.exe') -RedirectStandardInput $inputPath -RedirectStandardOutput $outputPath -WindowStyle Hidden -Wait -PassThru
if ($process.ExitCode -ne 0) { throw "Core budget oracle failed: $($process.ExitCode)" }
$answers = @(Get-Content -LiteralPath $outputPath)
if ($answers.Count -ne $graphs.Count * 25) { throw 'Wrong oracle answer count' }
$fixture = Join-Path $moduleRoot 'src/test/resources/core32-reference/budget-vectors.tsv'
$headers = @('# Bitcoin Core v32.1 spanning forest; genuine xoroshiro128++/RandomMixin; default cost model',
    "# cluster_linearize.h SHA256 $((Get-FileHash (Join-Path $include 'cluster_linearize.h') -Algorithm SHA256).Hash.ToLowerInvariant())",
    "# random.h SHA256 $((Get-FileHash $randomPath -Algorithm SHA256).Hash.ToLowerInvariant())",
    '# case<TAB>budget<TAB>seed<TAB>mode<TAB>cost<TAB>optimal<TAB>order<TAB>old; mode 0=new+post,1=old+post,2=reverse+fix+post,3=new raw,4=continue prior 75000 result')
[IO.File]::WriteAllLines($fixture, @($headers) + $answers)
Write-Output "Generated $($answers.Count) native Core budget/continuation/postlinearization vectors"
