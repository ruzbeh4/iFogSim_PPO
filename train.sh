#!/usr/bin/env bash
# Revised GA + shared-service PPO training launcher.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SIM_DIR="$ROOT/simulator"
AGENTS_DIR="$ROOT/agents"
BUILD_DIR="$SIM_DIR/out/shared_policy/classes"
SOURCE_LIST="$SIM_DIR/out/shared_policy/sources.txt"
JARS_DIR="$SIM_DIR/jars"
MAIN_CLASS="org.fog.test.perfeval.IndustrialIoTSimulationTrain"

EPISODES=200
START_SEED=1
PORT=5555
MAX_MIGRATIONS=2
MAX_ACTORS_PER_STEP=32
SIMULATION_TIME=1200
PLACEMENT_INTERVAL=10
SKIP_COMPILE=0
SHOW_EPISODE_SUMMARY=1
SHOW_SUCCESSFUL_DECISIONS=0
SHOW_SIMULATOR_DIAGNOSTICS=0
SHOW_PYTHON_PROGRESS=0
MODEL_PATH="$AGENTS_DIR/models/shared_ppo_model.pth"
CONVERGENCE_PATH="$AGENTS_DIR/results/shared_ppo_convergence.json"
RESET_TRAINING=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --episodes) EPISODES="$2"; shift 2 ;;
    --start-seed) START_SEED="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --max-migrations) MAX_MIGRATIONS="$2"; shift 2 ;;
    --max-actors-per-step) MAX_ACTORS_PER_STEP="$2"; shift 2 ;;
    --simulation-time) SIMULATION_TIME="$2"; shift 2 ;;
    --placement-interval) PLACEMENT_INTERVAL="$2"; shift 2 ;;
    --skip-compile) SKIP_COMPILE=1; shift ;;
    --no-episode-summary) SHOW_EPISODE_SUMMARY=0; shift ;;
    --show-successful-decisions) SHOW_SUCCESSFUL_DECISIONS=1; shift ;;
    --simulator-diagnostics) SHOW_SIMULATOR_DIAGNOSTICS=1; shift ;;
    --python-progress) SHOW_PYTHON_PROGRESS=1; shift ;;
    --model) MODEL_PATH="$2"; shift 2 ;;
    --convergence) CONVERGENCE_PATH="$2"; shift 2 ;;
    --reset-training) RESET_TRAINING=1; shift ;;
    *) echo "[train.sh] Unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ -x "$AGENTS_DIR/venv/bin/python" ]]; then
  PYTHON="$AGENTS_DIR/venv/bin/python"
elif command -v python3 >/dev/null 2>&1; then
  PYTHON="$(command -v python3)"
elif command -v python >/dev/null 2>&1; then
  PYTHON="$(command -v python)"
else
  echo "[train.sh] Python 3 was not found." >&2; exit 1
fi
command -v java >/dev/null 2>&1 || { echo "[train.sh] java was not found." >&2; exit 1; }
command -v javac >/dev/null 2>&1 || { echo "[train.sh] javac was not found." >&2; exit 1; }

if [[ "$SKIP_COMPILE" -eq 0 ]]; then
  echo "[train.sh] Compiling simulator..."
  mkdir -p "$BUILD_DIR"
  (
    cd "$SIM_DIR"
    find src -name '*.java' -print > "$SOURCE_LIST"
    javac -encoding UTF-8 -cp "$JARS_DIR/*" -d "$BUILD_DIR" @"$SOURCE_LIST"
  )
fi

if [[ "$RESET_TRAINING" -eq 1 ]]; then
  rm -f -- "$MODEL_PATH" "$CONVERGENCE_PATH"
  echo "[train.sh] Reset selected checkpoint and convergence history."
fi

SERVER_PID=""
cleanup() {
  if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

SERVER_ARGS=(--port "$PORT" --max-migrations "$MAX_MIGRATIONS" --model "$MODEL_PATH" --convergence "$CONVERGENCE_PATH")
if [[ "$SHOW_PYTHON_PROGRESS" -eq 1 ]]; then SERVER_ARGS+=(--progress); fi
"$PYTHON" -u "$AGENTS_DIR/shared_train_server.py" "${SERVER_ARGS[@]}" &
SERVER_PID=$!

ready=0
for _ in $(seq 1 40); do
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "[train.sh] Training server exited during startup." >&2; exit 1
  fi
  if "$PYTHON" -c "import socket; s=socket.create_connection(('localhost',$PORT),.2); s.close()" 2>/dev/null; then
    ready=1; break
  fi
  sleep 0.25
done
[[ "$ready" -eq 1 ]] || { echo "[train.sh] Server did not bind to port $PORT." >&2; exit 1; }

CP="$BUILD_DIR:$JARS_DIR/*"
for ((index=0; index<EPISODES; index++)); do
  seed=$((START_SEED + index))
  echo "[train.sh] Episode $((index + 1))/$EPISODES (seed=$seed)"
  java \
    -Difogsim.shared.policy=true \
    -Difogsim.bridge.port="$PORT" \
    -Difogsim.simulation.time="$SIMULATION_TIME" \
    -Difogsim.placement.interval="$PLACEMENT_INTERVAL" \
	-Difogsim.max.actors.per.step="$MAX_ACTORS_PER_STEP" \
    -Difogsim.log.summary="$([[ "$SHOW_EPISODE_SUMMARY" -eq 1 ]] && echo true || echo false)" \
    -Difogsim.log.decisions="$([[ "$SHOW_SUCCESSFUL_DECISIONS" -eq 1 ]] && echo true || echo false)" \
    -Difogsim.log.diagnostics="$([[ "$SHOW_SIMULATOR_DIAGNOSTICS" -eq 1 ]] && echo true || echo false)" \
    -cp "$CP" "$MAIN_CLASS" "$seed"
done

echo "[train.sh] Complete. Model: $MODEL_PATH"
