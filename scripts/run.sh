#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AGENTS_DIR="$ROOT/agents"
SIM_DIR="$ROOT/simulator"
CLASSES_DIR="$SIM_DIR/out/production/iFogSim_7"
JARS_DIR="$SIM_DIR/jars"
VENV_PYTHON="$AGENTS_DIR/venv/bin/python"

SCENARIO=1
AGENT=heuristic
PORT=5555

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scenario) SCENARIO="$2"; shift 2 ;;
    --agent) AGENT="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --help|-h)
      echo "Usage: scripts/run.sh [--scenario 1-4] [--agent heuristic|genetic|ppo] [--port N]"
      exit 0 ;;
    *) echo "[run] Unknown option: $1" >&2; exit 1 ;;
  esac
done

case "$SCENARIO" in
  1) MAIN_CLASS="org.fog.test.perfeval.IndustrialIoTSimulation" ;;
  2) MAIN_CLASS="org.fog.test.perfeval.IndustrialIoTSimulation2" ;;
  3) MAIN_CLASS="org.fog.test.perfeval.IndustrialIoTSimulation3" ;;
  4) MAIN_CLASS="org.fog.test.perfeval.IndustrialIoTSimulation4" ;;
  *) echo "[run] Unknown scenario: $SCENARIO" >&2; exit 1 ;;
esac

[[ -x "$VENV_PYTHON" ]] || { echo "[run] venv python missing: $VENV_PYTHON" >&2; exit 1; }
[[ -d "$CLASSES_DIR" ]] || { echo "[run] compiled classes missing: $CLASSES_DIR" >&2; exit 1; }

PYTHON_PID=""
cleanup() {
  if [[ -n "${PYTHON_PID}" ]] && kill -0 "$PYTHON_PID" 2>/dev/null; then
    kill "$PYTHON_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

echo "[run] scenario=$SCENARIO agent=$AGENT port=$PORT"
(
  cd "$AGENTS_DIR"
  "$VENV_PYTHON" -m servers.scenario --agent "$AGENT" --port "$PORT"
) &
PYTHON_PID=$!

for _ in $(seq 1 60); do
  if "$VENV_PYTHON" -c "import socket; s=socket.create_connection(('localhost',$PORT),.2); s.close()" 2>/dev/null; then
    break
  fi
  sleep 0.25
done

java -Difogsim.bridge.port="$PORT" -cp "$CLASSES_DIR:$JARS_DIR/*" "$MAIN_CLASS"
echo "[run] Done."
