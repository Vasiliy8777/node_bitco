param(
    [Parameter(Mandatory=$true)][string]$Snapshot,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [string]$ClasspathFile = 'target/rvn-benchmark-classpath.txt',
    [string]$Java = 'C:/Program Files/Java/jdk-21/bin/java.exe',
    [string]$Python = 'python',
    [ValidateRange(0,2147483647)][long]$Seconds = 3600
)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location -LiteralPath $repository
$activeNodes = @(Get-CimInstance Win32_Process -Filter "name='java.exe'" |
    Where-Object { $_.CommandLine -match 'ru\.bitcoin\.node\.rvn\.app\.RavencoinNodeApplication' })
if ($activeNodes.Count) { throw 'Stop the RVN node gracefully before starting this GPU experiment.' }
$snapshotPath = (Resolve-Path -LiteralPath $Snapshot).Path
$classpath = (Get-Content -LiteralPath $ClasspathFile -Raw).Trim()
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$outputPath = (Resolve-Path -LiteralPath $OutputDirectory).Path
$logPath = Join-Path $outputPath 'search.log'
if (Test-Path -LiteralPath $logPath) { throw 'Use a new output directory; previous experiment logs must be preserved.' }
$argumentPath = Join-Path $outputPath 'java.args'
$arguments = @(
    '-Xmx2g', '-cp', ('"' + $classpath.Replace('\','/') + '"'),
    'ru.bitcoin.node.rvn.app.mining.RvnRealTargetGpuBenchmark', 'run',
    ('"' + $snapshotPath.Replace('\','/') + '"'),
    ('"' + (Join-Path $repository 'rvn-pow/native/cuda/worker.py').Replace('\','/') + '"'),
    ('"' + (Join-Path $outputPath 'cuda').Replace('\','/') + '"'),
    ('"' + $Python.Replace('\','/') + '"'), [string]$Seconds
)
[IO.File]::WriteAllLines($argumentPath, $arguments, [Text.UTF8Encoding]::new($false))
$experiment = Start-Process -FilePath $Java -ArgumentList ('@' + $argumentPath) `
    -WorkingDirectory $repository -WindowStyle Hidden -RedirectStandardOutput $logPath `
    -RedirectStandardError (Join-Path $outputPath 'stderr.log') -PassThru
[IO.File]::WriteAllText((Join-Path $outputPath 'process-id.txt'), [string]$experiment.Id)
Write-Output "GPU experiment PID=$($experiment.Id); log=$logPath; seconds=$Seconds"
Write-Output "To stop gracefully: create an empty file $outputPath/stop-requested"
