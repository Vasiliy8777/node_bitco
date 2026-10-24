$ErrorActionPreference = 'Stop'
$rvnCommit = '6d48ae0175b10283248146ae3080e2ba70966739'
$rvnModule = Split-Path $PSScriptRoot -Parent
$rvnBuild = Join-Path $rvnModule 'target/verifier-reference'
New-Item -ItemType Directory -Path (Join-Path $rvnBuild 'assets') -Force | Out-Null
foreach ($rvnFile in @('LibBoolEE.h','LibBoolEE.cpp')) {
    Invoke-WebRequest "https://raw.githubusercontent.com/RavenProject/Ravencoin/$rvnCommit/src/$rvnFile" -OutFile (Join-Path $rvnBuild $rvnFile)
}
# Minimal ErrorReport declaration only; evaluation code is compiled unchanged.
$rvnStub = @'
#include <string>
#include <vector>
struct ErrorReport {
    enum ErrorType { NotSetError,InvalidQualifierName,EmptyString,LengthToLarge,InvalidSubExpressionFormula,InvalidSyntax,
        AssetDoesntExist,FailedToVerifyAgainstAddress,EmptySubExpression,UnknownOperator,ParenthesisParity,VariableNotFound };
    ErrorType type=NotSetError; std::string strDevData; std::vector<std::string> vecUserData;
};
'@
[IO.File]::WriteAllText((Join-Path $rvnBuild 'assets/assets.h'), $rvnStub)
$rvnVswhere = "${env:ProgramFiles(x86)}/Microsoft Visual Studio/Installer/vswhere.exe"
$rvnInstall = & $rvnVswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $rvnInstall) { throw 'MSVC x64 tools required for verifier fixture regeneration' }
$rvnVcvars = Join-Path $rvnInstall 'VC/Auxiliary/Build/vcvars64.bat'
$rvnHarness = Join-Path $PSScriptRoot 'verifier-reference.cpp'
$rvnBatch = "@echo off`r`ncall `"$rvnVcvars`" >nul`r`nif errorlevel 1 exit /b 1`r`ncl /nologo /EHsc /O2 /std:c++17 /I. `"$rvnHarness`" LibBoolEE.cpp /Fe:verifier-reference.exe`r`nexit /b %errorlevel%`r`n"
$rvnBatchPath = Join-Path $rvnBuild 'build.cmd'
[IO.File]::WriteAllText($rvnBatchPath, $rvnBatch)
Push-Location $rvnBuild
try { & $env:ComSpec /d /c $rvnBatchPath; if ($LASTEXITCODE -ne 0) { throw 'Verifier oracle build failed' } }
finally { Pop-Location }
$rvnExpressions = @('true','','AAA','!AAA','!!AAA','AAA|BBB&!CCC','(AAA|BBB)&!CCC','AAA|!AAA','AAA&!AAA',
    'AAA|','|AAA','AAA||BBB','AAA&&BBB','(AAA','AAA)','(AAA)(BBB)','!','()','false','1','#AAA','AAA BBB',
    '+AAA|BBB','?AAA&BBB','+AAA','+++AAA|BBB','AAA|?BBB','+AAA|+BBB&CCC','AAA&(+BBB|CCC)',
    "AAA`t|BBB", "`tAAA", 'AAA~BBB','AAA^BBB','AAA/BBB','AAA:BBB')
$rvnRequests = [System.Collections.Generic.List[string]]::new()
$rvnCases = [System.Collections.Generic.List[string]]::new()
foreach ($rvnExpression in $rvnExpressions) {
    $rvnHex = [Convert]::ToHexString([Text.Encoding]::ASCII.GetBytes($rvnExpression)).ToLowerInvariant()
    if (-not $rvnHex) { $rvnHex = '-' }
    foreach ($rvnMask in 0..7) { $rvnRequests.Add("$rvnHex $rvnMask"); $rvnCases.Add("$rvnHex`t$rvnMask") }
}
$rvnResults = @($rvnRequests | & (Join-Path $rvnBuild 'verifier-reference.exe'))
if ($LASTEXITCODE -ne 0 -or $rvnResults.Count -ne $rvnCases.Count) { throw 'Verifier oracle output mismatch' }
$rvnLines = [System.Collections.Generic.List[string]]::new()
$rvnLines.Add("# Core $rvnCommit LibBoolEE expression oracle; known-valid AAA/BBB/CCC qualifiers; hex mask valid result")
for ($rvnIndex=0; $rvnIndex -lt $rvnCases.Count; $rvnIndex++) {
    $rvnFields = $rvnResults[$rvnIndex] -split ' '
    $rvnLines.Add($rvnCases[$rvnIndex] + "`t" + $rvnFields[0] + "`t" + $rvnFields[1])
}
$rvnOutput = Join-Path $rvnModule 'src/test/resources/core-reference/verifier-vectors.tsv'
[IO.File]::WriteAllLines($rvnOutput, $rvnLines, [Text.UTF8Encoding]::new($false))
Write-Output "Generated $($rvnCases.Count) executed Core verifier cases"
