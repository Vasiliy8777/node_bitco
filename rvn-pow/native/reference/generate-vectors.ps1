$ErrorActionPreference = 'Stop'
$coreCommit = '6d48ae0175b10283248146ae3080e2ba70966739'
$moduleRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$buildDir = Join-Path $moduleRoot 'target/core-reference'
New-Item -ItemType Directory -Path $buildDir -Force | Out-Null
$algorithms = @('blake','bmw','groestl','jh','keccak','skein','luffa','cubehash','shavite','simd','echo','hamsi','fugue','shabal','whirlpool')
$files = @('sph_sha2big.c','tiger.cpp','aes_helper.c','hamsi_helper.c','md_helper.c','sph_md_helper.c','sph_types.h','sph_sha2.h','sph_tiger.h')
foreach ($algorithm in $algorithms) { $files += "$algorithm.c"; $files += "sph_$algorithm.h" }
foreach ($file in $files) {
    $destination = Join-Path $buildDir $file
    Invoke-WebRequest "https://raw.githubusercontent.com/RavenProject/Ravencoin/$coreCommit/src/algo/$file" -OutFile $destination
}
$vswhere = "${env:ProgramFiles(x86)}/Microsoft Visual Studio/Installer/vswhere.exe"
$installation = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $installation) { throw 'MSVC x64 tools are required only to regenerate these test fixtures' }
$vcvars = Join-Path $installation 'VC/Auxiliary/Build/vcvars64.bat'
$compilerInputs = (($algorithms | ForEach-Object { "$_.c" }) + @('sph_sha2big.c','tiger.cpp')) -join ' '
$oracle = Join-Path $PSScriptRoot 'x16-reference.c'
$batch = "@echo off`r`ncall `"$vcvars`" >nul`r`nif errorlevel 1 exit /b 1`r`ncl /nologo /O2 /TC /I. `"$oracle`" $compilerInputs /Fe:x16-reference.exe > compile.log 2>&1`r`nexit /b %errorlevel%`r`n"
$batchPath = Join-Path $buildDir 'build-reference.cmd'
[IO.File]::WriteAllText($batchPath, $batch)
Push-Location $buildDir
try {
    & $env:ComSpec /d /c $batchPath
    if ($LASTEXITCODE -ne 0) { Get-Content compile.log -Tail 30; throw 'Core reference build failed' }
    $vectors = & ./x16-reference.exe
    if ($LASTEXITCODE -ne 0) { throw 'Core reference failed' }
} finally { Pop-Location }
$resources = Join-Path $moduleRoot 'src/test/resources/core-reference'
New-Item -ItemType Directory -Path $resources -Force | Out-Null
[IO.File]::WriteAllLines((Join-Path $resources 'x16-vectors.tsv'), [string[]]$vectors)
Write-Output "Generated $($vectors.Count - 1) reference vectors from pinned Core sources"
