$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path $PSScriptRoot -Parent
$directory = Join-Path $moduleRoot 'target/core32-txgraph-reference'
# Reuse the genuine Core TxGraph build, regenerating its independent baseline too.
& (Join-Path $PSScriptRoot 'generate-txgraph-vectors.ps1')
$random = [Random]::new(321131)
$commands = [Collections.Generic.List[string]]::new()
for ($scenario=0;$scenario -lt 512;$scenario++) {
    $countLimit = 2 + $random.Next(15)
    $weightLimit = 1000 + $random.Next(12000)
    $commands.Add("new $countLimit $weightLimit")
    $chains = [Collections.Generic.List[object]]::new()
    $nextId = 1
    for ($group=0;$group -lt 5;$group++) {
        $chain = [Collections.Generic.List[int]]::new()
        $length = 1 + $random.Next([Math]::Min($countLimit,8))
        for ($depth=0;$depth -lt $length;$depth++) {
            $id=$nextId; $nextId++
            $fee=$random.Next(-10000,50000)
            $weight=1+$random.Next([Math]::Max(1,[int]($weightLimit/$length)))
            if ($depth -eq 0) {$commands.Add("add $id $fee $weight 0")}
            else {$commands.Add("add $id $fee $weight 1 $($chain[$depth-1])")}
            $chain.Add($id)
        }
        $chains.Add($chain.ToArray())
    }
    $commands.Add('work 1000000000')
    for ($parentGroup=0;$parentGroup -lt 4;$parentGroup++) {
        for ($childGroup=$parentGroup+1;$childGroup -lt 5;$childGroup++) {
            if ($random.Next(3) -eq 0) {continue}
            $parent=$chains[$parentGroup][$random.Next($chains[$parentGroup].Length)]
            $child=$chains[$childGroup][$random.Next($chains[$childGroup].Length)]
            $commands.Add("dep $parent $child")
        }
    }
    $commands.Add("trim $scenario")
    $commands.Add('check')
}
$inputPath=Join-Path $directory 'trim-input.txt'
$outputPath=Join-Path $directory 'trim-output.txt'
[IO.File]::WriteAllLines($inputPath,$commands)
$process=Start-Process -FilePath (Join-Path $directory 'txgraph-reference.exe') -RedirectStandardInput $inputPath -RedirectStandardOutput $outputPath -WindowStyle Hidden -Wait -PassThru
if ($process.ExitCode -ne 0) {throw "Trim oracle failed: $($process.ExitCode)"}
$answers=@(Get-Content $outputPath)
if ($answers.Count -ne 512) {throw 'Wrong trim reference answer count'}
$resources=Join-Path $moduleRoot 'src/test/resources/core32-reference'
[IO.File]::WriteAllLines((Join-Path $resources 'trim-operations.txt'),@('# Bitcoin Core v32.1 TxGraph::Trim; five materialized chains plus deferred dependencies')+$commands)
[IO.File]::WriteAllLines((Join-Path $resources 'trim-removed.tsv'),@('# Bitcoin Core v32.1 TxGraph::Trim; sequence and removed transaction ids')+$answers)
Write-Output "CORE32_TRIM scenarios=$($answers.Count)"
