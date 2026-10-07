$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$session = Get-Content -LiteralPath (Join-Path $projectRoot 'target/gpu-mining/session.json') -Raw | ConvertFrom-Json
$worker = if ($session.minerPid -gt 0) { Get-CimInstance Win32_Process -Filter "ProcessId=$($session.minerPid)" }
if ($worker -and $worker.CommandLine.Contains((Join-Path $PSScriptRoot 'miner.py'))) {
    Stop-Process -Id $session.minerPid
}
$node = Get-CimInstance Win32_Process -Filter "ProcessId=$($session.nodePid)"
$jar = Join-Path $projectRoot 'app/target/app-0.0.1-SNAPSHOT.jar'
if ($node -and $node.CommandLine.Contains($jar)) {
    & $session.java --add-modules jdk.attach (Join-Path $PSScriptRoot 'StopNode.java') ([string]$session.nodePid)
    if ($LASTEXITCODE -ne 0) { throw 'Graceful node shutdown failed; inspect node logs. Node was not forcibly terminated.' }
    Write-Output "Requested graceful node shutdown, PID $($session.nodePid)."
}
