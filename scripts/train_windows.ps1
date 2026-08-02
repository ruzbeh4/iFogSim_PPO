param(
    [int]$Episodes = 200,
    [long]$StartSeed = 1,
    [int]$Port = 5555,
    [int]$MaxMigrations = 2,
    [int]$MaxActorsPerStep = 32,
    [int]$SimulationTime = 1200,
    [double]$PlacementInterval = 10,
    [string]$RunName = "shared_ppo",
    [string]$ModelPath = "",
    [switch]$ResetTraining,
    [switch]$SkipCompile
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Simulator = Join-Path $Root "simulator"
$Agents = Join-Path $Root "agents"
$BuildDir = Join-Path $Simulator "out\shared_policy\classes"
$SourceList = Join-Path $Simulator "out\shared_policy\sources.txt"
$Jars = Join-Path $Simulator "jars"
$MainClass = "org.fog.test.perfeval.IndustrialIoTSimulationTrain"

$JavaCommand = Get-Command java -ErrorAction SilentlyContinue
$JavacCommand = Get-Command javac -ErrorAction SilentlyContinue
if ($JavaCommand -and $JavacCommand) {
    $Java = $JavaCommand.Source
    $Javac = $JavacCommand.Source
} else {
    $Javac = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE ".jdks") `
        -Recurse -Filter "javac.exe" -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $Javac) { throw "JDK not found." }
    $Java = Join-Path (Split-Path $Javac) "java.exe"
}

$VenvPython = Join-Path $Agents "venv\Scripts\python.exe"
$Python = if (Test-Path -LiteralPath $VenvPython) { $VenvPython } else { (Get-Command python -ErrorAction Stop).Source }

Push-Location $Agents
$ResultsDir = & $Python -c "from utils.results_paths import make_run_dir; print(make_run_dir('single', '$RunName'))"
Pop-Location
$RunModelPath = Join-Path $ResultsDir "model.pth"
$ConvergencePath = Join-Path $ResultsDir "convergence.json"

if ([string]::IsNullOrWhiteSpace($ModelPath)) {
    if (Test-Path -LiteralPath $RunModelPath) {
        $ModelPath = $RunModelPath
    } else {
        Push-Location $Agents
        $PrevModel = & $Python -c "from utils.results_paths import latest_model_path; p=latest_model_path('shared_ppo'); print(p or '')"
        Pop-Location
        if (-not [string]::IsNullOrWhiteSpace($PrevModel) -and (Test-Path -LiteralPath $PrevModel)) {
            Copy-Item -LiteralPath $PrevModel -Destination $RunModelPath -Force
            Write-Host "[train] Seeded model from $PrevModel"
        }
        $ModelPath = $RunModelPath
    }
} else {
    if (-not [System.IO.Path]::IsPathRooted($ModelPath)) {
        $ModelPath = Join-Path $Root $ModelPath
    }
    if ($ModelPath -ne $RunModelPath) {
        Copy-Item -LiteralPath $ModelPath -Destination $RunModelPath -Force
        $ModelPath = $RunModelPath
    }
}

if (-not $SkipCompile) {
    New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
    Push-Location $Simulator
    Get-ChildItem -Path src -Filter *.java -Recurse | ForEach-Object { $_.FullName } | Set-Content -Path $SourceList
    & $Javac -encoding UTF-8 -cp "$Jars\*" -d $BuildDir "@$SourceList"
    Pop-Location
}

if ($ResetTraining) {
    Remove-Item -Force -ErrorAction SilentlyContinue $RunModelPath, $ConvergencePath
    $ModelPath = $RunModelPath
}

$server = Start-Process -FilePath $Python -WorkingDirectory $Agents -PassThru -ArgumentList @(
    "-u", "-m", "servers.train",
    "--port", "$Port",
    "--max-migrations", "$MaxMigrations",
    "--model", "$ModelPath",
    "--convergence", "$ConvergencePath",
    "--results-dir", "$ResultsDir"
)

try {
    $ready = $false
    for ($i = 0; $i -lt 40; $i++) {
        try {
            $client = New-Object System.Net.Sockets.TcpClient
            $client.Connect("localhost", $Port)
            $client.Close()
            $ready = $true
            break
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }
    if (-not $ready) { throw "Training server failed to bind port $Port" }

    $cp = "$BuildDir;$Jars\*"
    for ($index = 0; $index -lt $Episodes; $index++) {
        $seed = $StartSeed + $index
        Write-Host "[train] Episode $($index + 1)/$Episodes (seed=$seed)"
        & $Java `
            "-Difogsim.shared.policy=true" `
            "-Difogsim.bridge.port=$Port" `
            "-Difogsim.simulation.time=$SimulationTime" `
            "-Difogsim.placement.interval=$PlacementInterval" `
            "-Difogsim.max.actors.per.step=$MaxActorsPerStep" `
            "-Difogsim.log.summary=true" `
            "-cp" $cp $MainClass "$seed"
    }
} finally {
    if ($server -and -not $server.HasExited) {
        Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "[train] Complete. Results: $ResultsDir"
