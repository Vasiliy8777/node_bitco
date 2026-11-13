param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path $PSScriptRoot -Parent
$workspaceRoot = Split-Path $moduleRoot -Parent
$directory = Join-Path $moduleRoot 'target/core32-txgraph-reference'
$include = Join-Path $directory 'include'
New-Item -ItemType Directory -Force -Path $directory | Out-Null
if (!(Test-Path $include)) { Copy-Item -LiteralPath (Join-Path $moduleRoot 'target/core32-budget-reference/include') -Destination $include -Recurse }
foreach ($source in @('txgraph.h','txgraph.cpp','util/vector.h')) {
    $destination = Join-Path $include $source
    if (!(Test-Path $destination)) { Invoke-WebRequest ('https://raw.githubusercontent.com/bitcoin/bitcoin/v32.1/src/' + $source) -OutFile $destination }
}
[IO.File]::WriteAllText((Join-Path $include 'util/check.h'), "#pragma once`n#include <cassert>`n#define Assume(e) ((e) ? true : (assert(e), false))`n#define Assert(e) assert(e)`n")
$memoryPath = Join-Path $include 'memusage.h'
if (!(Select-String -LiteralPath $memoryPath -Pattern 'MallocUsage' -Quiet)) {
    Add-Content -LiteralPath $memoryPath -Value 'namespace memusage { inline std::size_t MallocUsage(std::size_t size) { return size; } }'
}
$randomPath = Join-Path $include 'random.h'
if (!(Select-String -LiteralPath $randomPath -Pattern 'class FastRandomContext' -Quiet)) {
    Add-Content -LiteralPath $randomPath -Value 'class FastRandomContext : public InsecureRandomContext { public: FastRandomContext() : InsecureRandomContext(1001) {} template<typename I> I rand() { return this->template randbits<std::numeric_limits<I>::digits>(); } };'
}
# Only the outer graph RNG is deterministic here. SFL's seeded xoroshiro and
# bit-buffer remain genuine Core code. Queries optimize fully, so graph outputs
# do not depend on the outer RNG or allocator shim; no memory-size claim is made.
$vswhere = "${env:ProgramFiles(x86)}/Microsoft Visual Studio/Installer/vswhere.exe"
$installation = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (!$installation) { throw 'MSVC x64 required for oracle regeneration' }
$vcvars = Join-Path $installation 'VC/Auxiliary/Build/vcvars64.bat'
$source = Join-Path $PSScriptRoot 'txgraph-reference.cpp'
$batchPath = Join-Path $directory 'build-reference.cmd'
$batch = "@echo off`r`ncall `"$vcvars`" >nul`r`nif errorlevel 1 exit /b 1`r`ncl /nologo /O2 /EHsc /std:c++latest /I`"$include`" `"$source`" /Fe:txgraph-reference.exe > compile.log 2>&1`r`nexit /b %errorlevel%`r`n"
[IO.File]::WriteAllText($batchPath,$batch)
if (!$SkipBuild) {
    Push-Location $directory
    try { & $env:ComSpec /d /c $batchPath; if ($LASTEXITCODE -ne 0) { Get-Content compile.log -Tail 20; throw 'TxGraph reference compilation failed' } }
    finally { Pop-Location }
}
$commands = [Collections.Generic.List[string]]::new()
$random = [Random]::new(320202)
$sequence = 0
for ($scenario = 0; $scenario -lt 24; $scenario++) {
    $commands.Add('new')
    $mainIds = [Collections.Generic.HashSet[int]]::new()
    $stageIds = $null
    $parents = @{}
    $nextId = 1
    for ($step = 0; $step -lt 150; $step++) {
        if ($null -ne $stageIds) { $ids = $stageIds } else { $ids = $mainIds }
        $choice = $random.Next(100)
        if ($ids.Count -eq 0 -or ($choice -lt 42 -and $ids.Count -lt 30 -and $nextId -lt 230)) {
            $id = $nextId; $nextId++
            $dependencies = @($ids | Where-Object { $random.Next(7) -eq 0 } | Sort-Object)
            $parents[$id] = @($dependencies)
            $fee = $random.Next(-10000,50001); $weight = $random.Next(100,4001)
            $commands.Add("add $id $fee $weight $($dependencies.Count) $($dependencies -join ' ')")
            [void]$ids.Add($id)
        } elseif ($choice -lt 63) {
            $root = @($ids)[$random.Next($ids.Count)]
            # Real mempool removals remove all ancestors (confirmation) or all
            # descendants (eviction), matching TxGraph's documented contract.
            $remove = [Collections.Generic.HashSet[int]]::new(); [void]$remove.Add($root)
            $upstream = $random.Next(2) -eq 0
            do {
                $changed = $false
                foreach ($id in @($ids)) {
                    $links = @($parents[$id] | Where-Object { $ids.Contains($_) })
                    if ($upstream -and $remove.Contains($id)) {
                        foreach ($parent in $links) { if ($remove.Add($parent)) {$changed=$true} }
                    } elseif (!$upstream -and @($links | Where-Object {$remove.Contains($_)}).Count -gt 0) {
                        if ($remove.Add($id)) {$changed=$true}
                    }
                }
            } while ($changed)
            foreach ($id in @($remove | Sort-Object)) { $commands.Add("remove $id"); [void]$ids.Remove($id) }
        } elseif ($choice -lt 76) {
            $id = @($ids)[$random.Next($ids.Count)]; $fee = $random.Next(-20000,50001)
            $commands.Add("fee $id $fee")
        } elseif ($null -eq $stageIds) {
            $commands.Add('start'); $stageIds = [Collections.Generic.HashSet[int]]::new($mainIds)
        } else {
            if ($random.Next(2) -eq 0) { $commands.Add('commit'); $mainIds = $stageIds }
            else { $commands.Add('abort') }
            $stageIds = $null
        }
        $commands.Add("query $sequence"); $sequence++
    }
    if ($null -ne $stageIds) { $commands.Add('abort') }
}
$inputPath = Join-Path $directory 'input.txt'; $outputPath = Join-Path $directory 'output.txt'
[IO.File]::WriteAllLines($inputPath,$commands)
$process = Start-Process -FilePath (Join-Path $directory 'txgraph-reference.exe') -RedirectStandardInput $inputPath -RedirectStandardOutput $outputPath -WindowStyle Hidden -Wait -PassThru
if ($process.ExitCode -ne 0) { throw "TxGraph oracle failed: $($process.ExitCode)" }
$answers = @(Get-Content $outputPath)
if ($answers.Count -ne $sequence) { throw 'Wrong graph oracle answer count' }
$resources = Join-Path $moduleRoot 'src/test/resources/core32-reference'
$hashes = @('# Bitcoin Core v32.1 TxGraph; fully optimized snapshots; deterministic outer RNG and allocator shims',
    "# txgraph.cpp SHA256 $((Get-FileHash (Join-Path $include 'txgraph.cpp') -Algorithm SHA256).Hash.ToLowerInvariant())",
    "# txgraph.h SHA256 $((Get-FileHash (Join-Path $include 'txgraph.h') -Algorithm SHA256).Hash.ToLowerInvariant())")
[IO.File]::WriteAllLines((Join-Path $resources 'txgraph-operations.txt'),@($hashes)+$commands)
[IO.File]::WriteAllLines((Join-Path $resources 'txgraph-snapshots.tsv'),@($hashes)+$answers)
Write-Output "Generated $sequence native Core main/staging/abort/commit snapshots"
