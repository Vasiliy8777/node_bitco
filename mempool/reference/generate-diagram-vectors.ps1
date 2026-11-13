$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path $PSScriptRoot -Parent
$directory = Join-Path $moduleRoot 'target/core32-txgraph-reference'
& (Join-Path $PSScriptRoot 'generate-txgraph-vectors.ps1')
$commands = [Collections.Generic.List[string]]::new()
$rows=Get-Content (Join-Path $moduleRoot 'src/test/resources/core32-reference/cluster-vectors.tsv') | Where-Object {$_ -notmatch '^#'}
$sequence=0
foreach($row in $rows) {
    $fields=$row.Split("`t")
    if(!$fields[1]) {continue}
    $vertices=$fields[1].Split(';')
    $commands.Add('new')
    for($id=0;$id -lt $vertices.Length;$id++) {
        $parts=$vertices[$id].Split(':');$rate=$parts[0].Split(',')
        $parents=if($parts[1]) {@($parts[1].Split(','))} else {@()}
        $commands.Add("add $id $($rate[0]) $($rate[1]) $($parents.Count) $($parents -join ' ')")
    }
    $commands.Add('add 240 100 400 0') # Unchanged component must cancel.
    $commands.Add('work 1000000000')
    $commands.Add('start')
    $last=$vertices.Length-1
    $commands.Add("remove $last")
    $parts=$vertices[$last].Split(':');$rate=$parts[0].Split(',')
    $parents=if($parts[1]) {@($parts[1].Split(','))} else {@()}
    $new=$vertices.Length;$newFee=[long]$rate[0]+1000;$newWeight=[long]$rate[1]+1
    $commands.Add("add $new $newFee $newWeight $($parents.Count) $($parents -join ' ')")
    $commands.Add('work 1000000000')
    $commands.Add("diagram $sequence $last $new")
    $commands.Add('abort')
    $sequence++
}
$inputPath=Join-Path $directory 'diagram-input.txt';$outputPath=Join-Path $directory 'diagram-output.txt'
[IO.File]::WriteAllLines($inputPath,$commands)
$process=Start-Process -FilePath (Join-Path $directory 'txgraph-reference.exe') -RedirectStandardInput $inputPath -RedirectStandardOutput $outputPath -WindowStyle Hidden -Wait -PassThru
if($process.ExitCode -ne 0) {throw "Diagram oracle failed: $($process.ExitCode)"}
$answers=@(Get-Content $outputPath)
if($answers.Count -ne $sequence) {throw 'Wrong diagram reference answer count'}
$resources=Join-Path $moduleRoot 'src/test/resources/core32-reference'
[IO.File]::WriteAllLines((Join-Path $resources 'diagram-operations.txt'),@('# Bitcoin Core v32.1; fully optimized main/staging diagrams; unaffected component cancellation')+$commands)
[IO.File]::WriteAllLines((Join-Path $resources 'diagram-results.tsv'),@('# sequence<TAB>main diagram<TAB>staging diagram')+$answers)
Write-Output "CORE32_DIAGRAM scenarios=$sequence"
