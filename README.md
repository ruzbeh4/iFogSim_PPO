# iFogSim_PPO

Train and compare microservice placement/migration agents (Heuristic, Genetic, Shared PPO) against an iFogSim2 industrial scenario over a TCP/JSON bridge.

## Requirements

- JDK with `javac` / `java`
- Python 3
- Dependencies in `agents/requirements.txt` (PyTorch required for Shared PPO)

```bash
cd agents
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
cd ..
```

## Process

1. Start a Python TCP server (`train` or `compare`).
2. For each seed, run `IndustrialIoTSimulationTrain` in Java.
3. At step 0 the agent returns initial placements (`shared_initial`).
4. On each placement tick the agent returns migrations (`shared_step`).
5. At episode end Java sends `results`; train mode updates PPO and saves outputs under `agents/results/<date>/`.

Wrappers in the repo root call the real scripts:

- `./train.sh` → `scripts/train.sh`
- `./compare.sh` → `scripts/compare.sh`

## Train Shared PPO

```bash
bash train.sh \
  --episodes 100 \
  --start-seed 1 \
  --run-name shared_ppo_scratch \
  --reset-training \
  --placement-init genetic \
  --max-migrations 4 \
  --placement-interval 5
```

Useful flags:

| Flag | Meaning |
|------|---------|
| `--episodes` / `--start-seed` | Episode count and first seed |
| `--run-name` | Output folder name under `agents/results/<date>/single/` |
| `--placement-init` | `genetic`, `heuristic`, or `bad_heuristic` |
| `--model` | Load an existing `model.pth` |
| `--reset-training` | Delete this run’s model and convergence history |
| `--max-migrations` | Cap migrations per step |
| `--placement-interval` | Java decision tick interval |
| `--simulation-time` | Episode length |
| `--port` | Bridge port (default `5555`) |
| `--skip-compile` | Skip Java compile if classes are already built |

Outputs: `agents/results/<date>/single/<run-name>/` (`model.pth`, `convergence.json`, episode JSON, plots).

## Compare agents

Train (or point `--model` at) a checkpoint first, then:

```bash
bash compare.sh \
  --episodes 100 \
  --start-seed 1 \
  --agents genetic_heuristic,ppo \
  --model agents/results/<date>/single/<run>/model.pth \
  --run-name ga_heur_vs_ppo_seeds1-100 \
  --skip-compile
```

Agent IDs (`--agents`, comma-separated):

| ID | Initial placement | Online migration |
|----|-------------------|------------------|
| `heuristic` | Heuristic | none |
| `genetic` | Genetic | none |
| `genetic_heuristic` | Genetic | Heuristic |
| `ppo` | Genetic | Shared PPO |
| `heuristic_heuristic` | Heuristic | Heuristic |
| `heuristic_ppo` | Heuristic | Shared PPO |
| `heur_v2_heuristic` | Heuristic-v2 | Heuristic |
| `heur_v2_ppo` | Heuristic-v2 | Shared PPO |

Useful flags: same timing/seed options as train, plus `--agents`, `--run-name`, `--model`. Compare defaults to port `5556`.

Outputs: `agents/results/<date>/compare/<run-name>/` (per-agent episode JSON and `plots/agent_comparison.png`).

Keep seeds, simulation time, and placement interval fixed across agents for a fair comparison.
