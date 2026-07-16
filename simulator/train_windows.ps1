<#
.SYNOPSIS
    Automated Training Loop for PPO on Windows (Global Python version).
.DESCRIPTION
    Boots the Python training server in the background using the globally installed
    Python interpreter, then loops the Java iFogSim2 simulator N times to train
    the agent. Cleanly kills the server on exit.
#>

param (
    [int]$episodes = 200,
    [string]$agent = "ppo_train",
    [int]$port = 5555
)

# ── Project Layout ───────────────────────────────────────────────────────────
$ROOT = $PSScriptRoot
$AGENTS_DIR  = Join-Path $ROOT "agents"
$SIM_DIR     = Join-Path $ROOT "simulator"
$CLASSES_DIR = Join-Path $SIM_DIR "out\production\simulator"
$JARS_DIR    = Join-Path $SIM_DIR "jars"

$MAIN_CLASS  = "org.fog.test.perfeval.IndustrialIoTSimulationTrain"

# ── Validation ───────────────────────────────────────────────────────────────
if (-Not (Test-Path $CLASSES_DIR)) {
    Write-Host "[train_windows.ps1] ERROR: compiled classes not found at $CLASSES_DIR" -ForegroundColor Red
    Write-Host "Build the simulator in IntelliJ first (Build -> Build Project)."
    exit 1
}

# ── Build Classpath (Windows uses ';' as separator) ──────────────────────────
$CP = "$CLASSES_DIR;$JARS_DIR\*"

# ── Execution & Cleanup Block ────────────────────────────────────────────────
$serverProcess = $null

try {
    Write-Host "=========================================================="
    Write-Host " Launching Python Train Server (Agent: $agent)"
    Write-Host "=========================================================="

    # Start Python server in the background using the GLOBAL python interpreter
    $serverArgs = @("$AGENTS_DIR\train_server.py", "--agent", $agent, "--port", $port)
    $serverProcess = Start-Process -FilePath "python" -ArgumentList $serverArgs -PassThru -NoNewWindow

    Start-Sleep -Seconds 2 # Wait for server to bind to the port

    if ($serverProcess.HasExited) {
        Write-Host "ERROR: Python server failed to start." -ForegroundColor Red
        exit 1
    }

    Write-Host "=========================================================="
    Write-Host " Commencing Training Loop: $episodes Episodes"
    Write-Host "=========================================================="

    for ($i = 1; $i -le $episodes; $i++) {
        Write-Host "`n>>> Starting Episode $i / $episodes <<<" -ForegroundColor Cyan

        # Run Java synchronously so the script waits for the episode to finish
        $javaArgs = @("-cp", $CP, $MAIN_CLASS, $i)

        $javaProcess = Start-Process -FilePath "java" -ArgumentList $javaArgs -Wait -NoNewWindow -PassThru

        if ($javaProcess.ExitCode -ne 0) {
            Write-Host "Episode $i terminated with an error (Code: $($javaProcess.ExitCode))." -ForegroundColor Yellow
        }

        Start-Sleep -Seconds 1 # Socket cleanup breather
    }

    Write-Host "`nTraining Complete! Check agents\results\convergence.json" -ForegroundColor Green

} finally {
    # ── Cleanup (Triggered on completion or if you press Ctrl+C) ─────────────
    if ($serverProcess -and -Not $serverProcess.HasExited) {
        Write-Host "`n[train_windows.ps1] Stopping Train Server (PID $($serverProcess.Id))..." -ForegroundColor DarkGray
        Stop-Process -Id $serverProcess.Id -Force
    }
}