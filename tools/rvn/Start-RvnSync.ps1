#Requires -Version 7.0
param(
    [string] $ColdDirectory = 'K:/RVNJavaNjde',
    [string] $HotDirectory = 'C:/RVNJavaNjde',
    [string] $Peers = '',
    [string] $Java = 'java'
)

$ErrorActionPreference = 'Stop'
$rvnRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$rvnJar = Join-Path $rvnRepository 'rvn-app/target/rvn-app-0.0.1-SNAPSHOT.jar'
$rvnRunFile = Join-Path $rvnRepository 'rvn-app/target/rvn-sync-process.json'
$rvnCold = [IO.Path]::GetFullPath($ColdDirectory)
$rvnHot = [IO.Path]::GetFullPath($HotDirectory)
if (-not (Test-Path -LiteralPath $rvnJar -PathType Leaf)) {
    throw 'Build first: mvn -pl rvn-app -am package'
}
if (Test-Path -LiteralPath $rvnRunFile) {
    $rvnPrevious = Get-Content -LiteralPath $rvnRunFile -Raw | ConvertFrom-Json
    $rvnExisting = Get-CimInstance Win32_Process -Filter "ProcessId = $($rvnPrevious.processId)"
    if ($null -ne $rvnExisting -and $rvnExisting.CreationDate.ToUniversalTime() -eq ([DateTime] $rvnPrevious.createdUtc).ToUniversalTime()) {
        throw "Recorded RVN process is already running: PID $($rvnExisting.ProcessId). Check its log before restarting."
    }
}

# ArgumentList passes paths and peer lists directly to Java, without a shell or
# string-built command. The hidden child keeps syncing after this launcher exits.
$rvnStart = [Diagnostics.ProcessStartInfo]::new()
$rvnStart.FileName = @(Get-Command $Java -CommandType Application)[0].Source
$rvnStart.WorkingDirectory = $rvnRepository
$rvnStart.UseShellExecute = $false
$rvnStart.CreateNoWindow = $true
$rvnStart.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
$rvnArguments = @(
    '-Xmx4g', '-XX:MaxDirectMemorySize=512m',
    "-Djava.io.tmpdir=$([IO.Path]::Combine($rvnHot, 'tmp'))",
    '-jar', $rvnJar,
    "--ravencoin.data-directory=$rvnCold", "--ravencoin.storage.hot-directory=$rvnHot",
    '--ravencoin.sync.enabled=true', '--ravencoin.sync.blocks-enabled=true',
    '--ravencoin.validation.native-replay-enabled=true',
    '--ravencoin.validation.canonical-asset-replay-enabled=true',
    '--ravencoin.mining.enabled=false', '--ravencoin.mining.cuda.enabled=false',
    '--ravencoin.p2p.probe-enabled=false', '--ravencoin.p2p.session-enabled=false'
)
if (-not [string]::IsNullOrWhiteSpace($Peers)) { $rvnArguments += "--ravencoin.sync.peers=$Peers" }
foreach ($rvnArgument in $rvnArguments) { $rvnStart.ArgumentList.Add($rvnArgument) }
New-Item -ItemType Directory -Path ([IO.Path]::Combine($rvnHot, 'tmp')) -Force | Out-Null
$rvnProcess = [Diagnostics.Process]::Start($rvnStart)
$rvnIdentity = Get-CimInstance Win32_Process -Filter "ProcessId = $($rvnProcess.Id)"
if ($null -eq $rvnIdentity) { throw 'RVN process exited immediately; check the JVM and node log.' }
@{
    processId = $rvnProcess.Id
    createdUtc = $rvnIdentity.CreationDate.ToUniversalTime().ToString('o')
    jar = $rvnJar
    hotDirectory = $rvnHot
    coldDirectory = $rvnCold
    log = [IO.Path]::Combine($rvnCold, 'logs', 'rvn-node.log')
    mode = 'sync-and-supported-subset-replay; mining disabled'
} | ConvertTo-Json | Set-Content -LiteralPath $rvnRunFile -Encoding utf8
Write-Output "RVN sync started: PID $($rvnProcess.Id)"
Write-Output "Log: $([IO.Path]::Combine($rvnCold, 'logs', 'rvn-node.log'))"
Write-Output 'Mainnet mining remains disabled until full chainstate validation and production relay are complete.'
