param(
    [ValidateSet('auto', 'mainnet', 'testnet', 'testnet4', 'regtest')][string]$Network = 'auto',
    [string]$Java = 'C:/Program Files/Java/jdk-21/bin/java.exe',
    [string]$Python = 'python',
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$wallet = $env:BITCOIN_MINING_PAYOUT_ADDRESS
if ([string]::IsNullOrWhiteSpace($wallet)) { throw 'Set BITCOIN_MINING_PAYOUT_ADDRESS before starting mining.' }
if ($Network -eq 'auto') {
    if ($wallet.StartsWith('bc1')) { $Network = 'mainnet' }
    elseif ($wallet.StartsWith('tb1')) { $Network = 'testnet4' }
    elseif ($wallet.StartsWith('bcrt1')) { $Network = 'regtest' }
    else { throw 'Cannot infer network from payout address; specify -Network.' }
}
$expectedPrefix = switch ($Network) { 'mainnet' {'bc1'} 'regtest' {'bcrt1'} default {'tb1'} }
if (!$wallet.StartsWith($expectedPrefix)) { throw "Payout address does not match $Network." }
if (![string]::IsNullOrEmpty($env:BITCOIN_NETWORK) -and $env:BITCOIN_NETWORK -ne $Network) {
    throw 'BITCOIN_NETWORK conflicts with selected network.'
}
$rpcPort = switch ($Network) { 'mainnet' {8332} 'testnet' {18336} 'testnet4' {48332} 'regtest' {18446} }
foreach ($listenPort in @(3333, $rpcPort)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $listenPort -ErrorAction SilentlyContinue) {
        throw "Port $listenPort is already in use; stop the existing node/miner before this launch."
    }
}
if ([string]::IsNullOrWhiteSpace($env:BITCOIN_STRATUM_PASSWORD)) {
    $env:BITCOIN_STRATUM_PASSWORD = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
}
Push-Location $projectRoot
try {
    if (!$SkipBuild) {
        & (Join-Path $projectRoot 'mvnw.cmd') -pl app -am package '-DskipTests'
        if ($LASTEXITCODE -ne 0) { throw 'Node build failed.' }
    }
    & $Python (Join-Path $PSScriptRoot 'miner.py') --self-test
    if ($LASTEXITCODE -ne 0) { throw 'CUDA self-test failed.' }
    $jar = Join-Path $projectRoot 'app/target/app-0.0.1-SNAPSHOT.jar'
    if (!(Test-Path -LiteralPath $jar)) { throw 'Executable node jar is missing; run without -SkipBuild.' }
    $logDirectory = Join-Path $projectRoot ('target/gpu-mining/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    $nodeArguments = @('-Xms1g', '-Xmx4g', '-Dspring.application.admin.enabled=true',
        '-jar', ('"' + $jar + '"'), "--spring.profiles.active=$Network,gpu", "--bitcoin.network=$Network",
        "--bitcoin.rpc.port=$rpcPort", '--bitcoin.stratum.bind=127.0.0.1', '--bitcoin.stratum.port=3333', '--bitcoin.gpu-miner.enabled=false')
    $nodeLaunch = @{ FilePath=$Java; ArgumentList=$nodeArguments; WorkingDirectory=$projectRoot;
        WindowStyle='Hidden'; PassThru=$true; RedirectStandardOutput=(Join-Path $logDirectory 'node.log');
        RedirectStandardError=(Join-Path $logDirectory 'node-error.log') }
    $node = Start-Process @nodeLaunch
    $sessionPath = Join-Path $projectRoot 'target/gpu-mining/session.json'
    @{ nodePid=$node.Id; minerPid=0; network=$Network; rpcPort=$rpcPort; logDirectory=$logDirectory; java=$Java } |
        ConvertTo-Json | Set-Content -LiteralPath $sessionPath
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        Start-Sleep -Milliseconds 500
        $node.Refresh()
        if ($node.HasExited) { throw "Node startup failed; inspect $logDirectory/node.log and node-error.log." }
        $listening = Get-NetTCPConnection -State Listen -LocalPort 3333 -ErrorAction SilentlyContinue
    } while (!$listening -and [DateTime]::UtcNow -lt $deadline)
    if (!$listening) { throw "Stratum did not bind within 60 seconds; node PID $($node.Id), logs $logDirectory." }
    $workerLaunch = @{ FilePath=(Get-Command $Python).Source;
        ArgumentList=@('-u', ('"' + (Join-Path $PSScriptRoot 'miner.py') + '"'));
        WorkingDirectory=$projectRoot; WindowStyle='Hidden'; PassThru=$true;
        RedirectStandardOutput=(Join-Path $logDirectory 'miner.log'); RedirectStandardError=(Join-Path $logDirectory 'miner-error.log') }
    $worker = Start-Process @workerLaunch
    @{ nodePid=$node.Id; minerPid=$worker.Id; network=$Network; rpcPort=$rpcPort; logDirectory=$logDirectory; java=$Java } |
        ConvertTo-Json | Set-Content -LiteralPath $sessionPath
    if ($worker.WaitForExit(1500)) { throw "CUDA worker exited; inspect $logDirectory/miner-error.log. Node remains running; use Stop-GpuMining.ps1 to stop it." }
    Write-Output "Started $Network node PID $($node.Id), CUDA worker PID $($worker.Id). Logs: $logDirectory"
    Write-Output 'Worker waits for verified chain synchronization before hashing. Stop with tools/gpu-miner/Stop-GpuMining.ps1.'
} finally { Pop-Location }
