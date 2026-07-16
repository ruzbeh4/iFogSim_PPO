#!/usr/bin/env bash
# run.sh — start the Python agent server, wait for it, then launch the Java simulator.
#
# Usage:
#   ./run.sh                          # scenario 1, heuristic agent
#   ./run.sh --scenario 4 --agent ppo
#   ./run.sh --scenario 2 --agent genetic
#   ./run.sh --help
#
# Options:
#   --scenario  1|2|3|4   (default: 1)
#   --agent     heuristic|genetic|ppo  (default: heuristic)
#   --port      TCP port for the bridge  (default: 5555)

set -euo pipefail

# ── project layout ──────────────────────────────────────────────────────────
ROOT="$(cd "$(dirname "$0")" && pwd)"
AGENTS_DIR="$ROOT/agents"
SIM_DIR="$ROOT/simulator"
CLASSES_DIR="$SIM_DIR/out/production/iFogSim_7"
JARS_DIR="$SIM_DIR/jars"
VENV_PYTHON="$AGENTS_DIR/venv/bin/python"

# ── defaults ────────────────────────────────────────────────────────────────
SCENARIO=1
AGENT=heuristic
PORT=5555

# ── argument parsing ─────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        --scenario) SCENARIO="$2"; shift 2 ;;
        --agent)    AGENT="$2";    shift 2 ;;
        --port)     PORT="$2";     shift 2 ;;
        --help|-h)
            sed -n '/^# Usage/,/^[^#]/p' "$0" | grep '^#' | sed 's/^# \?//'
            exit 0 ;;
        *) echo "[run.sh] Unknown option: $1"; exit 1 ;;
    esac
done

# ── scenario → main class ───────────────────────────────────────────────────
case "$SCENARIO" in
    1) MAIN_CLASS="org.fog.test.perfeval.IndustrialIoTSimulation"  ;;
    2) MAIN_CLASS="org.fog.test.perfeval.IndustrialIoTSimulation2" ;;
    3) MAIN_CLASS="org.fog.test.perfeval.IndustrialIoTSimulation3" ;;
    4) MAIN_CLASS="org.fog.test.perfeval.IndustrialIoTSimulation4" ;;
    *) echo "[run.sh] Unknown scenario: $SCENARIO (must be 1-4)"; exit 1 ;;
esac

# ── validation ───────────────────────────────────────────────────────────────
if [[ ! -f "$VENV_PYTHON" ]]; then
    echo "[run.sh] ERROR: venv python not found at $VENV_PYTHON"
    echo "         Run:  cd agents && python -m venv venv && venv/bin/pip install -r requirements.txt"
    exit 1
fi

if [[ ! -d "$CLASSES_DIR" ]]; then
    echo "[run.sh] ERROR: compiled classes not found at $CLASSES_DIR"
    echo "         Build the simulator in IntelliJ first (Build → Build Project)."
    exit 1
fi

# ── build Java classpath from all jars ──────────────────────────────────────
CP="$CLASSES_DIR"
for jar in "$JARS_DIR"/*.jar; do
    CP="$CP:$jar"
done

# ── cleanup on exit ──────────────────────────────────────────────────────────
PYTHON_PID=""
cleanup() {
    if [[ -n "$PYTHON_PID" ]] && kill -0 "$PYTHON_PID" 2>/dev/null; then
        echo ""
        echo "[run.sh] Stopping Python server (pid $PYTHON_PID)..."
        kill "$PYTHON_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

# ── start Python server ──────────────────────────────────────────────────────
echo "[run.sh] Scenario  : $SCENARIO  ($MAIN_CLASS)"
echo "[run.sh] Agent     : $AGENT"
echo "[run.sh] Port      : $PORT"
echo ""

echo "[run.sh] Starting Python agent server..."
"$VENV_PYTHON" "$AGENTS_DIR/server.py" --agent "$AGENT" --port "$PORT" &
PYTHON_PID=$!

# wait until the server is accepting connections (up to 15 s)
echo "[run.sh] Waiting for server on port $PORT..."
for i in $(seq 1 30); do
    if nc -z localhost "$PORT" 2>/dev/null; then
        echo "[run.sh] Server ready."
        break
    fi
    if ! kill -0 "$PYTHON_PID" 2>/dev/null; then
        echo "[run.sh] ERROR: Python server exited unexpectedly."
        exit 1
    fi
    sleep 0.5
done

if ! nc -z localhost "$PORT" 2>/dev/null; then
    echo "[run.sh] ERROR: Server did not come up within 15 s."
    exit 1
fi

echo ""
echo "[run.sh] Launching simulator..."
echo "──────────────────────────────────────────────────────────"

java -cp "$CP" "$MAIN_CLASS"
SIM_EXIT=$?

echo "──────────────────────────────────────────────────────────"
echo "[run.sh] Simulator exited (code $SIM_EXIT)."

# give the Python server a moment to finish writing results before cleanup kills it
sleep 2

echo ""
echo "[run.sh] Done. Press Enter to close..."
read -r _

exit $SIM_EXIT
