<#
.SYNOPSIS
    Automated PPO training loop on Windows – with robust server startup.
#>

param (
    [int]$episodes = 200,
    [string]$agent = "ppo_train",
    [int]$port = 5555
)

# ── Java 21 path (adjust if needed) ──
$JAVA_EXE = "C:\Users\ruzbeh\.jdks\ms-21.0.11\bin\java.exe"

# ── Project layout ──
$ROOT = $PSScriptRoot
$AGENTS_DIR  = Join-Path $ROOT "agents"
$SIM_DIR     = Join-Path $ROOT "simulator"
$CLASSES_DIR = Join-Path $SIM_DIR "out\production\simulator"
$JARS_DIR    = Join-Path $SIM_DIR "jars"
$MAIN_CLASS  = "org.fog.test.perfeval.IndustrialIoTSimulationTrain"

# ── Validation ──
if (-Not (Test-Path $CLASSES_DIR)) {
    Write-Host "[ERROR] Compiled classes not found at $CLASSES_DIR" -ForegroundColor Red
    exit 1
}
if (-Not (Test-Path $JAVA_EXE)) {
    Write-Host "[ERROR] Java 21 not found at $JAVA_EXE" -ForegroundColor Red
    exit 1
}

$CP = "$CLASSES_DIR;$JARS_DIR\*"

# ── Helper: Check if port is open ──
function Test-Port {
    param($hostname = "localhost", $port)
    try {
        $tcp = New-Object System.Net.Sockets.TcpClient
        $tcp.Connect($hostname, $port)
        $tcp.Close()
        return $true
    } catch {
        return $false
    }
}

# ── Start Python server ──
Write-Host "=========================================================="
Write-Host " Launching Python Train Server (Agent: $agent)"
Write-Host "=========================================================="

$serverArgs = @("$AGENTS_DIR\train_server.py", "--agent", $agent, "--port", $port)
$serverProcess = Start-Process -FilePath "python" -ArgumentList $serverArgs -PassThru -NoNewWindow -RedirectStandardOutput "$AGENTS_DIR\server_stdout.log" -RedirectStandardError "$AGENTS_DIR\server_stderr.log"

# Wait for server to be ready (max 15 seconds)
$maxAttempts = 30
$attempt = 0
$ready = $false
Write-Host "Waiting for server to bind to port $port..."
while ($attempt -lt $maxAttempts) {
    Start-Sleep -Milliseconds 500
    $attempt++
    if (Test-Port -port $port) {
        $ready = $true
        break
    }
    # Check if the process has exited
    if ($serverProcess.HasExited) {
        Write-Host "[ERROR] Python server exited prematurely. Check $AGENTS_DIR\server_stderr.log" -ForegroundColor Red
        Get-Content "$AGENTS_DIR\server_stderr.log" -ErrorAction SilentlyContinue
        exit 1
    }
}

if (-not $ready) {
    Write-Host "[ERROR] Server did not become ready within 15 seconds." -ForegroundColor Red
    if ($serverProcess -and -Not $serverProcess.HasExited) {
        Stop-Process -Id $serverProcess.Id -Force
    }
    exit 1
}

Write-Host "[OK] Server is ready on port $port."

# ── Training loop ──
Write-Host "=========================================================="
Write-Host " Commencing Training Loop: $episodes Episodes"
Write-Host "=========================================================="

try {
    for ($i = 1; $i -le $episodes; $i++) {
        Write-Host "`n>>> Episode $i / $episodes <<<" -ForegroundColor Cyan
        $javaArgs = @("-cp", $CP, $MAIN_CLASS, $i)
        $proc = Start-Process -FilePath $JAVA_EXE -ArgumentList $javaArgs -Wait -NoNewWindow -PassThru
        if ($proc.ExitCode -ne 0) {
            Write-Host "Episode $i exited with code $($proc.ExitCode)." -ForegroundColor Yellow
        }
        Start-Sleep -Seconds 1   # allow socket cleanup between runs
    }
    Write-Host "`nTraining Complete! Check agents\results\convergence.json" -ForegroundColor Green
} finally {
    # ── Cleanup ──
    if ($serverProcess -and -Not $serverProcess.HasExited) {
        Write-Host "`nStopping Train Server (PID $($serverProcess.Id))..." -ForegroundColor DarkGray
        Stop-Process -Id $serverProcess.Id -Force
    }
    # Optionally show logs
    Write-Host "Server stdout (last 5 lines):"
    Get-Content "$AGENTS_DIR\server_stdout.log" -Tail 5 -ErrorAction SilentlyContinue
    Write-Host "Server stderr (if any):"
    Get-Content "$AGENTS_DIR\server_stderr.log" -ErrorAction SilentlyContinue
}