$ErrorActionPreference = 'Stop'
$rvnModule = Split-Path $PSScriptRoot -Parent
$rvnBuild = Join-Path $rvnModule 'target/qualifier-reference'
New-Item -ItemType Directory -Path $rvnBuild -Force | Out-Null
$rvnSource = Join-Path $rvnBuild 'assets.cpp'
Invoke-WebRequest 'https://raw.githubusercontent.com/RavenProject/Ravencoin/v4.8.0/src/assets/assets.cpp' -OutFile $rvnSource
$rvnText = [IO.File]::ReadAllText($rvnSource)
function Get-RvnMethod([string]$Signature) {
    $rvnStart = $rvnText.IndexOf($Signature, [StringComparison]::Ordinal)
    if ($rvnStart -lt 0) { throw "Missing Core method $Signature" }
    $rvnBrace = $rvnText.IndexOf('{', $rvnStart)
    # Source methods contain no unmatched braces in comments/string literals.
    $rvnDepth = 0
    for ($rvnIndex = $rvnBrace; $rvnIndex -lt $rvnText.Length; $rvnIndex++) {
        if ($rvnText[$rvnIndex] -eq '{') { $rvnDepth++ }
        if ($rvnText[$rvnIndex] -eq '}') { $rvnDepth--; if ($rvnDepth -eq 0) { return $rvnText.Substring($rvnStart, $rvnIndex - $rvnStart + 1) } }
    }
    throw "Unterminated Core method $Signature"
}
$rvnMethods = @(
    (Get-RvnMethod 'bool CAssetsCache::AddQualifierAddress('),
    (Get-RvnMethod 'bool CAssetsCache::RemoveQualifierAddress('),
    (Get-RvnMethod 'bool CAssetsCache::CheckForAddressQualifier(')
)
$rvnDump = Get-RvnMethod 'bool CAssetsCache::DumpCacheToDatabase('
$rvnStart = $rvnDump.IndexOf('// Add the new qualifier commands to the database')
$rvnEnd = $rvnDump.IndexOf('// Add new restricted address commands')
if ($rvnStart -lt 0 -or $rvnEnd -lt $rvnStart) { throw 'Core qualifier flush boundaries changed' }
$rvnMethods += "bool CAssetsCache::DumpQualifiers() { bool dirty=false; std::string message;`n" + $rvnDump.Substring($rvnStart, $rvnEnd - $rvnStart) + "`n ClearDirtyCache(); return true; }"
[IO.File]::WriteAllText((Join-Path $rvnBuild 'qualifier-methods.inc'), ($rvnMethods -join "`n"))
$rvnVswhere = "${env:ProgramFiles(x86)}/Microsoft Visual Studio/Installer/vswhere.exe"
$rvnInstall = & $rvnVswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $rvnInstall) { throw 'MSVC x64 tools required' }
$rvnVcvars = Join-Path $rvnInstall 'VC/Auxiliary/Build/vcvars64.bat'
$rvnHarness = Join-Path $PSScriptRoot 'qualifier-reference.cpp'
$rvnBatch = "@echo off`r`ncall `"$rvnVcvars`" >nul`r`nif errorlevel 1 exit /b 1`r`ncl /nologo /EHsc /O2 /std:c++17 /I. `"$rvnHarness`" /Fe:qualifier-reference.exe`r`nexit /b %errorlevel%`r`n"
$rvnBatchPath = Join-Path $rvnBuild 'build.cmd'
[IO.File]::WriteAllText($rvnBatchPath, $rvnBatch)
Push-Location $rvnBuild
try { & $env:ComSpec /d /c $rvnBatchPath; if ($LASTEXITCODE -ne 0) { throw 'Qualifier oracle build failed' } }
finally { Pop-Location }
$rvnResults = @(& (Join-Path $rvnBuild 'qualifier-reference.exe'))
if ($LASTEXITCODE -ne 0) { throw 'Qualifier oracle execution failed' }
$rvnOutput = Join-Path $rvnModule 'src/test/resources/core-reference/qualifier-vectors.tsv'
$rvnHeader = '# Core v4.8.0 source SHA256 ' + (Get-FileHash $rvnSource -Algorithm SHA256).Hash + '; label root child'
[IO.File]::WriteAllLines($rvnOutput, @($rvnHeader) + $rvnResults, [Text.UTF8Encoding]::new($false))
$rvnResults
