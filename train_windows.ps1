<# Revised GA + shared-service PPO training launcher. #>
param(
    [int]$Episodes = 200,
    [long]$StartSeed = 1,
    [int]$Port = 5555,
    [int]$MaxMigrations = 2,
	[int]$MaxActorsPerStep = 32,
    [int]$SimulationTime = 1200,
    [double]$PlacementInterval = 10,
    [bool]$ShowEpisodeSummary = $true,
    [bool]$ShowSuccessfulDecisions = $false,
    [bool]$ShowSimulatorDiagnostics = $false,
    [bool]$ShowPythonProgress = $false,
    [string]$ModelPath = "agents\models\shared_ppo_model.pth",
    [string]$ConvergencePath = "agents\results\shared_ppo_convergence.json",
    [switch]$ResetTraining,
    [switch]$SkipCompile
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
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
    if (-not $Javac) {
        throw "A JDK was not found on PATH or under $env:USERPROFILE\.jdks."
    }
    $Java = Join-Path (Split-Path $Javac) "java.exe"
}
$VenvPython = Join-Path $Agents "venv\Scripts\python.exe"
$Python = if (Test-Path -LiteralPath $VenvPython) {
    $VenvPython
} else {
    (Get-Command python -ErrorAction Stop).Source
}

if ([string]::IsNullOrWhiteSpace($ModelPath)) {
    $ModelPath = Join-Path $Agents "models\shared_ppo_model.pth"
} elseif (-not [System.IO.Path]::IsPathRooted($ModelPath)) {
    $ModelPath = Join-Path $Root $ModelPath
}
if ([string]::IsNullOrWhiteSpace($ConvergencePath)) {
    $ConvergencePath = Join-Path $Agents "results\shared_ppo_convergence.json"
} elseif (-not [System.IO.Path]::IsPathRooted($ConvergencePath)) {
    $ConvergencePath = Join-Path $Root $ConvergencePath
}

if ($ResetTraining) {
    foreach ($Path in @($ModelPath, $ConvergencePath)) {
        if (Test-Path -LiteralPath $Path) {
            Remove-Item -LiteralPath $Path -Force
            Write-Host "[train_windows] Reset: removed $Path" -ForegroundColor Yellow
        }
    }
}

if (-not $SkipCompile) {
    Write-Host "[train_windows] Compiling simulator..."
    New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
    New-Item -ItemType Directory -Force -Path (Split-Path $SourceList) | Out-Null
    $Sources = Get-ChildItem -LiteralPath (Join-Path $Simulator "src") -Recurse -Filter "*.java" |
        ForEach-Object { $_.FullName }
    [System.IO.File]::WriteAllLines(
        $SourceList, $Sources, [System.Text.UTF8Encoding]::new($false))
    & $Javac -encoding UTF-8 -cp "$Jars\*" -d $BuildDir "@$SourceList"
    if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }
}

$Server = $null
try {
    $ServerArgs = @(
        "-u", (Join-Path $Agents "shared_train_server.py"),
        "--port", $Port,
        "--max-migrations", $MaxMigrations,
        "--model", $ModelPath,
        "--convergence", $ConvergencePath
    )
    if ($ShowPythonProgress) { $ServerArgs += "--progress" }
    $Server = Start-Process -FilePath $Python -ArgumentList $ServerArgs -PassThru -NoNewWindow

    $Ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        Start-Sleep -Milliseconds 250
        if ($Server.HasExited) { throw "Training server exited during startup." }
        try {
            $Client = [System.Net.Sockets.TcpClient]::new()
            $Client.Connect("localhost", $Port)
            $Client.Dispose()
            $Ready = $true
            break
        } catch { }
    }
    if (-not $Ready) { throw "Training server did not bind to port $Port." }

    $Classpath = "$BuildDir;$Jars\*"
    $SummaryValue = $ShowEpisodeSummary.ToString().ToLowerInvariant()
    $DecisionValue = $ShowSuccessfulDecisions.ToString().ToLowerInvariant()
    $DiagnosticValue = $ShowSimulatorDiagnostics.ToString().ToLowerInvariant()
    for ($index = 0; $index -lt $Episodes; $index++) {
        $Seed = $StartSeed + $index
        Write-Host "[train_windows] Episode $($index + 1)/$Episodes (seed=$Seed)" -ForegroundColor Cyan
        $JavaArgs = @(
			"-Difogsim.shared.policy=true",
            "-Difogsim.bridge.port=$Port",
            "-Difogsim.simulation.time=$SimulationTime",
            "-Difogsim.placement.interval=$PlacementInterval",
			"-Difogsim.max.actors.per.step=$MaxActorsPerStep",
            "-Difogsim.log.summary=$SummaryValue",
            "-Difogsim.log.decisions=$DecisionValue",
            "-Difogsim.log.diagnostics=$DiagnosticValue",
            "-cp", $Classpath, $MainClass, $Seed
        )
        & $Java @JavaArgs
        if ($LASTEXITCODE -ne 0) { throw "Episode seed $Seed failed with exit code $LASTEXITCODE" }
    }
    Write-Host "[train_windows] Complete. Model: $ModelPath" -ForegroundColor Green
} finally {
    if ($Server -and -not $Server.HasExited) {
        Stop-Process -Id $Server.Id -Force
    }
}
