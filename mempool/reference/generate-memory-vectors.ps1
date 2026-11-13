param()
$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path $PSScriptRoot -Parent
$directory = Join-Path $moduleRoot 'target/core32-memory-reference'
$include = Join-Path $directory 'include'
New-Item -ItemType Directory -Force -Path $directory | Out-Null
if (!(Test-Path $include)) {
    Copy-Item -LiteralPath (Join-Path $moduleRoot 'target/core32-txgraph-reference/include') -Destination $include -Recurse
}
# Use the complete, unmodified Core allocator estimator. Only declarations of
# unrelated container types are stubbed; none is instantiated by TxGraph.
$memory = Join-Path $include 'memusage.h'
Invoke-WebRequest 'https://raw.githubusercontent.com/bitcoin/bitcoin/v32.1/src/memusage.h' -OutFile $memory
[IO.File]::WriteAllText((Join-Path $include 'indirectmap.h'), "#pragma once`ntemplate<class X,class Y> class indirectmap;`n")
[IO.File]::WriteAllText((Join-Path $include 'prevector.h'), "#pragma once`ntemplate<unsigned int N,class X,class S,class D> class prevector;`n")
New-Item -ItemType Directory -Force -Path (Join-Path $include 'support/allocators') | Out-Null
[IO.File]::WriteAllText((Join-Path $include 'support/allocators/pool.h'), "#pragma once`n#include <cstddef>`ntemplate<class T,std::size_t B,std::size_t A> class PoolAllocator;`n")
$source = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'txgraph-reference.cpp') -Raw
$source = $source.Replace("std::cout << '\n';", "std::cout << '\t' << graph->GetMainMemoryUsage() << '\n';")
$sourcePath = Join-Path $directory 'memory-reference.cpp'
[IO.File]::WriteAllText($sourcePath,$source)
$vswhere = "${env:ProgramFiles(x86)}/Microsoft Visual Studio/Installer/vswhere.exe"
$installation = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (!$installation) { throw 'MSVC x64 required for oracle regeneration' }
$vcvars = Join-Path $installation 'VC/Auxiliary/Build/vcvars64.bat'
$batchPath = Join-Path $directory 'build-reference.cmd'
[IO.File]::WriteAllText($batchPath,"@echo off`r`ncall `"$vcvars`" >nul`r`nif errorlevel 1 exit /b 1`r`ncl /nologo /O2 /EHsc /std:c++latest /I`"$include`" `"$sourcePath`" /Fe:memory-reference.exe > compile.log 2>&1`r`nexit /b %errorlevel%`r`n")
Push-Location $directory
try {
    & $env:ComSpec /d /c $batchPath
    if ($LASTEXITCODE -ne 0) { Get-Content compile.log -Tail 30; throw 'Memory reference compilation failed' }
} finally { Pop-Location }
$inputPath = Join-Path $moduleRoot 'target/core32-txgraph-reference/input.txt'
$outputPath = Join-Path $directory 'output.txt'
$process = Start-Process -FilePath (Join-Path $directory 'memory-reference.exe') -RedirectStandardInput $inputPath -RedirectStandardOutput $outputPath -WindowStyle Hidden -Wait -PassThru
if ($process.ExitCode -ne 0) { throw "Memory reference failed: $($process.ExitCode)" }
$answers = @(Get-Content $outputPath)
if ($answers.Count -ne 3600) { throw 'Wrong memory reference answer count' }
$hash = (Get-FileHash -LiteralPath $memory -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllLines((Join-Path $moduleRoot 'src/test/resources/core32-reference/txgraph-memory.tsv'),
    @('# Bitcoin Core v32.1 TxGraph + genuine memusage.h; release x64 ABI; deterministic outer RNG',"# memusage.h SHA256 $hash") + $answers)
Write-Output "CORE32_GRAPH_MEMORY snapshots=$($answers.Count)"
