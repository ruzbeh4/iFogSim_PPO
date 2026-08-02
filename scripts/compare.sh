#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SIM_DIR="$ROOT/simulator"
AGENTS_DIR="$ROOT/agents"
BUILD_DIR="$SIM_DIR/out/shared_policy/classes"
SOURCE_LIST="$SIM_DIR/out/shared_policy/sources.txt"
JARS_DIR="$SIM_DIR/jars"
MAIN_CLASS="org.fog.test.perfeval.IndustrialIoTSimulationTrain"

EPISODES=10
START_SEED=1
PORT=5556
MAX_MIGRATIONS=4
MAX_ACTORS_PER_STEP=32
SIMULATION_TIME=1200
PLACEMENT_INTERVAL=5
SKIP_COMPILE=0
AGENTS="heuristic,genetic,ppo"
RUN_NAME=""
MODEL_PATH=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --episodes) EPISODES="$2"; shift 2 ;;
    --start-seed) START_SEED="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --max-migrations) MAX_MIGRATIONS="$2"; shift 2 ;;
    --max-actors-per-step) MAX_ACTORS_PER_STEP="$2"; shift 2 ;;
    --simulation-time) SIMULATION_TIME="$2"; shift 2 ;;
    --placement-interval) PLACEMENT_INTERVAL="$2"; shift 2 ;;
    --agents) AGENTS="$2"; shift 2 ;;
    --run-name) RUN_NAME="$2"; shift 2 ;;
    --model) MODEL_PATH="$2"; shift 2 ;;
    --skip-compile) SKIP_COMPILE=1; shift ;;
    *) echo "[compare] Unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ -x "$AGENTS_DIR/venv/bin/python" ]]; then
  PYTHON="$AGENTS_DIR/venv/bin/python"
elif command -v python3 >/dev/null 2>&1; then
  PYTHON="$(command -v python3)"
else
  echo "[compare] Python 3 was not found." >&2; exit 1
fi
command -v java >/dev/null 2>&1 || { echo "[compare] java was not found." >&2; exit 1; }
command -v javac >/dev/null 2>&1 || { echo "[compare] javac was not found." >&2; exit 1; }

if [[ -z "$RUN_NAME" ]]; then
  RUN_NAME="seeds${START_SEED}-$((START_SEED + EPISODES - 1))_$(date +%H%M%S)"
fi

COMPARE_ROOT="$(
  cd "$AGENTS_DIR"
  "$PYTHON" -c "from utils.results_paths import make_run_dir; print(make_run_dir('compare', '$RUN_NAME'))"
)"
mkdir -p "$COMPARE_ROOT/plots"
echo "[compare] results=$COMPARE_ROOT"

if [[ "$SKIP_COMPILE" -eq 0 ]]; then
  echo "[compare] Compiling simulator..."
  mkdir -p "$BUILD_DIR"
  (
    cd "$SIM_DIR"
    find src -name '*.java' -print > "$SOURCE_LIST"
    javac -encoding UTF-8 -cp "$JARS_DIR/*" -d "$BUILD_DIR" @"$SOURCE_LIST"
  )
fi

if [[ -z "$MODEL_PATH" ]]; then
  MODEL_PATH="$(
    cd "$AGENTS_DIR"
    "$PYTHON" -c "from utils.results_paths import latest_model_path; p=latest_model_path('shared_ppo'); print(p or '')"
  )"
fi
if [[ -z "$MODEL_PATH" || ! -f "$MODEL_PATH" ]]; then
  echo "[compare] WARNING: No shared_ppo model.pth found under results/<date>/single/"
else
  echo "[compare] model=$MODEL_PATH"
fi

IFS=',' read -r -a AGENT_LIST <<< "$AGENTS"
SERVER_PID=""
cleanup() {
  if [[ -n "${SERVER_PID}" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

CP="$BUILD_DIR:$JARS_DIR/*"
LOG_FILE="$COMPARE_ROOT/compare_run.log"
: > "$LOG_FILE"

for agent in "${AGENT_LIST[@]}"; do
  agent="$(echo "$agent" | tr -d '[:space:]')"
  [[ -n "$agent" ]] || continue
  results_dir="$COMPARE_ROOT/$agent"
  mkdir -p "$results_dir"
  echo "[compare] === agent=$agent episodes=$EPISODES ===" | tee -a "$LOG_FILE"

  cleanup
  (
    cd "$AGENTS_DIR"
    "$PYTHON" -u -m servers.compare \
      --agent "$agent" \
      --port "$PORT" \
      --model "$MODEL_PATH" \
      --results-dir "$results_dir" \
      --max-migrations "$MAX_MIGRATIONS"
  ) &
  SERVER_PID=$!

  ready=0
  for _ in $(seq 1 40); do
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      echo "[compare] Server exited during startup." >&2; exit 1
    fi
    if "$PYTHON" -c "import socket; s=socket.create_connection(('localhost',$PORT),.2); s.close()" 2>/dev/null; then
      ready=1; break
    fi
    sleep 0.25
  done
  [[ "$ready" -eq 1 ]] || { echo "[compare] Server did not bind to port $PORT." >&2; exit 1; }

  for ((index=0; index<EPISODES; index++)); do
    seed=$((START_SEED + index))
    echo "[compare] $agent episode $((index + 1))/$EPISODES (seed=$seed)" | tee -a "$LOG_FILE"
    java \
      -Difogsim.shared.policy=true \
      -Difogsim.bridge.port="$PORT" \
      -Difogsim.simulation.time="$SIMULATION_TIME" \
      -Difogsim.placement.interval="$PLACEMENT_INTERVAL" \
      -Difogsim.max.actors.per.step="$MAX_ACTORS_PER_STEP" \
      -Difogsim.log.summary=true \
      -Difogsim.log.decisions=false \
      -Difogsim.log.diagnostics=false \
      -cp "$CP" "$MAIN_CLASS" "$seed" | tee -a "$LOG_FILE"
  done

  cleanup
  SERVER_PID=""
done

PLOT_OUT="$COMPARE_ROOT/plots/agent_comparison.png"
(
  cd "$AGENTS_DIR"
  "$PYTHON" -m plots.comparison --results-root "$COMPARE_ROOT" --output "$PLOT_OUT"
)

echo "[compare] Complete."
echo "[compare] Results: $COMPARE_ROOT"
echo "[compare] Plot: $PLOT_OUT"
