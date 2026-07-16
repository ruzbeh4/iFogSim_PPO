#!/usr/bin/env bash
# train.sh — Automated Training Loop for PPO
# Usage: ./train.sh --episodes 500 --agent ppo_train

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
AGENTS_DIR="$ROOT/agents"
SIM_DIR="$ROOT/simulator"
CLASSES_DIR="$SIM_DIR/out/production/iFogSim_7"
JARS_DIR="$SIM_DIR/jars"
VENV_PYTHON="$AGENTS_DIR/venv/bin/python"

EPISODES=200
AGENT="ppo_train"
PORT=5555

while [[ $# -gt 0 ]]; do
    case "$1" in
        --episodes) EPISODES="$2"; shift 2 ;;
        --agent)    AGENT="$2";    shift 2 ;;
        --port)     PORT="$2";     shift 2 ;;
        *) echo "[train.sh] Unknown option: $1"; exit 1 ;;
    esac
done

MAIN_CLASS="org.fog.test.perfeval.IndustrialIoTSimulationTrain"

if [[ ! -f "$VENV_PYTHON" ]]; then
    echo "[train.sh] ERROR: venv python not found at $VENV_PYTHON"
    exit 1
fi

CP="$CLASSES_DIR"
for jar in "$JARS_DIR"/*.jar; do
    CP="$CP:$jar"
done

PYTHON_PID=""
cleanup() {
    if [[ -n "$PYTHON_PID" ]] && kill -0 "$PYTHON_PID" 2>/dev/null; then
        echo "[train.sh] Stopping Train Server (pid $PYTHON_PID)..."
        kill "$PYTHON_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

echo "=========================================================="
echo " Launching Python Train Server (Agent: $AGENT)"
echo "=========================================================="
"$VENV_PYTHON" "$AGENTS_DIR/train_server.py" --agent "$AGENT" --port "$PORT" &
PYTHON_PID=$!

sleep 2 # Wait for server to bind

echo "=========================================================="
echo " Commencing Training Loop: $EPISODES Episodes"
echo "=========================================================="

for EPISODE in $(seq 1 $EPISODES); do
    echo ">>> Starting Episode $EPISODE / $EPISODES <<<"
    # Pass the episode number to Java to mutate the random seeds
    java -cp "$CP" "$MAIN_CLASS" "$EPISODE"
    
    sleep 1 # Socket cleanup breather
done

echo "Training Complete! Check agents/results/convergence.json"