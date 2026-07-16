# iFogSim2
A Toolkit for Modeling and Simulation of Resource Management Techniques in Internet of Things, Edge and Fog Computing Environments with the following new features:
 * Mobility-support and Migration Management
   * Supporting real mobility datasets
   * Implementing different random mobility models 
 * Microservice Orchestration
 * Dynamic Distributed Clustering
 * Any Combinations of Above-mentioned Features
 * Full Compatibility with the Latest Version of the CloudSim (i.e., (https://github.com/Cloudslab/cloudsim/releases)) and [Previous iFogSim Version](https://github.com/Cloudslab/iFogSim1) and Tutorials

iFogSim2 currently encompasses several new usecases such as:
 * Audio Translation Scenario
 * Healthcare Scenario
 * Crowd-sensing Scenario

# How to run iFogSim2 ?
* Eclipse IDE:
  * Create a Java project
  * Inside the project directory, initialize an empty Git repository with the following command:
  ```
  git init
  ```
  * Add the Git repository of iFogSim2 as the `origin` remote:
  ```
  git remote add origin https://github.com/Cloudslab/iFogSim
  ```
  * Pull the contents of the repository to your machine:
  ```
  git pull origin main
  ```
  * Include the JARs to your project  
  * Run the example files (e.g. TranslationServiceFog_Clustering.java, CrowdSensing_Microservices_RandomMobility_Clustering.java) to get started

* IntelliJ IDEA:
  * Clone the iFogSim2 Git repository to desired folder:
  ```
  git clone https://github.com/Cloudslab/iFogSim
  ```
  * Select "project from existing resources" from the "File" drop-down menu
  * Verify the Java version
  * Verify the external libraries in the "JARs" Folder are added to the project
  * Run the example files (e.g. TranslationServiceFog_Clustering.java, CrowdSensing_Microservices_RandomMobility_Clustering.java) to get started


---

# Industry 4.0 Extension – Microservice Placement via Python Agents

This fork extends iFogSim2 with an **Industry 4.0 simulation scenario** and a
**Java ↔ Python TCP bridge** that lets any external algorithm (heuristic, genetic,
PPO/DRL) make placement decisions at runtime without modifying the simulator core.

## Project Status

| Component | Status |
|-----------|--------|
| TCP socket bridge (Java ↔ Python) | ✅ Working |
| Heuristic agent – Least-Loaded, edge-first | ✅ Working |
| Genetic Algorithm agent | ✅ Working |
| Results collection → JSON export | ✅ Working |
| Estimated E2E latency from placement decisions | ✅ Working |
| PPO / Deep Reinforcement Learning agent | 🔲 Next step |
| Comparison charts (latency, energy, migration) | 🔲 Next step |

## Scenario – Factory Floor Topology

```
Cloud  (CLOUD, level 0, 44 800 MIPS)
  ├── FogGW-0  (FCN, level 1, 4 000 MIPS, 100 ms to cloud)
  │     ├── IoT-0-0 / IoT-0-1 / IoT-0-2  (CLIENT, level 2, 20 ms to gateway)
  ├── FogGW-1  (FCN) ── IoT-1-0 / IoT-1-1 / IoT-1-2
  ├── FogGW-2  (FCN) ── IoT-2-0 / IoT-2-1 / IoT-2-2
  ├── FogGW-3  (FCN)  ← spare compute, no clients
  └── FogGW-4  (FCN)  ← spare compute, no clients
```

Each fog gateway holds exactly one SA+AC pair (2 800 MIPS) with 1 200 MIPS headroom,
creating genuine capacity scarcity that forces algorithms to make real placement tradeoffs.

Application pipeline (`industrial_iot`):

```
IoT_SENSOR →[UP]→ data_preprocessor →[UP]→ smart_analyzer
                       ↑                          ↓
                  ACTUATOR ←[DOWN]← actuator_controller
```

| Module | Placed by | MIPS | Role |
|--------|-----------|-----:|------|
| `data_preprocessor` | Pre-placed on CLIENT | 500 | Edge filter |
| `smart_analyzer` | Python agent | 2 000 | ML inference |
| `actuator_controller` | Python agent | 800 | Control loop |

## Metrics

Since iFogSim2 microservices mode does not wire up TimeKeeper loop tracking or
the CloudSim network counter, the following metrics are used for algorithm comparison:

| Metric | Source | Notes |
|--------|--------|-------|
| Total energy (J) | `FogDevice.getEnergyConsumption()` | Primary efficiency metric |
| Energy per device | same | Shows cloud vs edge load distribution |
| Cloud cost | `FogDevice.getTotalCost()` | Reflects cloud compute usage |
| Estimated E2E latency | Computed from placement level + topology constants | 20 ms fog / 120 ms cloud from IoT |
| Edge utilization % | Placement log | Fraction of modules placed on fog/IoT |

Results are saved automatically to `python_agent/results/<agent>_<timestamp>.json`
after each simulation run.

## File Structure

```
iFogSim_PPO/
├── src/org/fog/
│   ├── placement/
│   │   ├── PythonBridgePlacementLogic.java   ← TCP bridge + placement log
│   │   ├── PlacementLogicFactory.java        ← PYTHON_BRIDGE_PLACEMENT = 4
│   │   └── ClusteredMicroservicePlacementLogic.java  ← 2 fields private→protected
│   └── test/perfeval/
│       └── IndustrialIoTSimulation.java      ← Industry 4.0 scenario
│
└── python_agent/
    ├── server.py                 ← TCP server, dispatches to agents, saves results
    ├── requirements.txt          ← stdlib only; pip install torch numpy gymnasium for PPO
    └── agents/
        ├── __init__.py
        ├── base_agent.py         ← abstract base (implement decide(state) → action)
        ├── heuristic.py          ← Least-Loaded, edge-first (fog before cloud)
        └── genetic.py            ← GA: pop=50, gen=100, elitism 10%, latency+energy fitness
```

## How to Run

**Terminal 1 – start the Python agent server**

```bash
cd python_agent
python server.py --agent heuristic   # or --agent genetic
```

**Terminal 2 – run the Java simulation**

Open `IndustrialIoTSimulation.java` in IntelliJ → Run `main()`

The server shuts down automatically after receiving the simulation results.
Results are saved to `python_agent/results/<agent>_<timestamp>.json`.

## Bridge Protocol

Two message types, both newline-terminated JSON on `localhost:5555`.

**Placement request** (Java → Python, once per IoT sensor):

```
Java  →  {"step": N, "devices": [...], "requests": [...], "allModules": [...]}
Python→  {"placement": {"<requestId>": {"<module>": <deviceId>, ...}}}
```

**Results report** (Java → Python, once after simulation ends via JVM shutdown hook):

```
Java  →  {"type": "results", "simulationTime": N, "energyPerDevice": [...],
          "totalEnergy": N, "cloudCost": N, "numRequests": N, "placements": [...]}
Python→  {"status": "saved", "file": "<path>"}
```

Both sockets close after the results exchange.

## Adding a New Agent

```python
# python_agent/agents/ppo.py
from .base_agent import BasePlacementAgent

class PPOAgent(BasePlacementAgent):
    def decide(self, state: dict) -> dict:
        # 1. build observation vector from state["devices"] and state["requests"]
        # 2. run torch model forward pass
        # 3. return {"placement": {requestId: {module: deviceId}}}
        ...
```

Then register it in `server.py`:

```python
from agents import HeuristicAgent, GeneticAgent, PPOAgent
AGENTS["ppo"] = PPOAgent
```

Run: `python server.py --agent ppo`

---

# References
 * Redowan Mahmud, Samodha Pallewatta, Mohammad Goudarzi, and Rajkumar Buyya, <A href="https://arxiv.org/abs/2109.05636">iFogSim2: An Extended iFogSim Simulator for Mobility, Clustering, and Microservice Management in Edge and Fog Computing Environments</A>, Journal of Systems and Software (JSS), Volume 190, Pages: 1-17, ISSN:0164-1212, Elsevier Press, Amsterdam, The Netherlands, August 2022.
 * Harshit Gupta, Amir Vahid Dastjerdi , Soumya K. Ghosh, and Rajkumar Buyya, <A href="http://www.buyya.com/papers/iFogSim.pdf">iFogSim: A Toolkit for Modeling and Simulation of Resource Management Techniques in Internet of Things, Edge and Fog Computing Environments</A>, Software: Practice and Experience (SPE), Volume 47, Issue 9, Pages: 1275-1296, ISSN: 0038-0644, Wiley Press, New York, USA, September 2017.
 * Redowan Mahmud and Rajkumar Buyya, <A href="http://www.buyya.com/papers/iFogSim-Tut.pdf">Modelling and Simulation of Fog and Edge Computing Environments using iFogSim Toolkit</A>, Fog and Edge Computing: Principles and Paradigms, R. Buyya and S. Srirama (eds), 433-466pp, ISBN: 978-111-95-2498-4, Wiley Press, New York, USA, January 2019.

---

## Revised shared-policy PPO training

The original PPO experiment remains available, but its policy consumes every
movable service in one fixed vector and chooses one global `(service, device)`
pair. The revised training path is separate and does not change the existing
`server.py` / `IndustrialIoTSimulation4` inference path.

New entry points:

- `agents/shared_train_server.py` - persistent GA + shared PPO server
- `agents/agents/shared_ppo.py` - variable-size candidate-scoring actor/critic
- `SharedPolicyPPOBridgePlacementLogic.java` - revised simulator protocol
- `train.sh` and `train_windows.ps1` - compile, start the server, and run episodes

Run on Linux/macOS:

```bash
bash train.sh --episodes 200 --start-seed 1
```

Run on Windows PowerShell:

```powershell
.\train_windows.ps1 -Episodes 200 -StartSeed 1
```

Both launchers accept a port, simulation duration, placement interval, and
maximum migrations per step. They compile Java sources automatically unless
`--skip-compile` / `-SkipCompile` is supplied. The model is written to
`agents/models/shared_ppo_model.pth`; convergence data is written to
`agents/results/shared_ppo_convergence.json`.

The shared-policy mode is enabled by the launcher with
`-Difogsim.shared.policy=true`; it does not require a second Java entry class.
Critical requests are a seeded exponential arrival stream (mean 225 s per
client, alongside normal requests with mean 25 s). Each critical request gets a
seeded 300–500 ms per-task deadline inside the Industrial IoT scenario; it is
not a launcher parameter. Completion is recorded through iFogSim's loop timing
and QoS-success accounting. At simulation cutoff, only overdue unfinished
critical tasks count as misses; non-expired tasks are recorded as pending.

Plot a saved convergence history with:

```powershell
python agents\plot_shared_training.py
```

### Training terminal output

The shared-policy launcher keeps the terminal compact by default: it prints a
two-line trajectory summary containing energy, cloud cost, loop delay, mean
local actor reward, GA placements, and accepted/rejected PPO migrations.

On PowerShell, the independent controls are:

```powershell
# Print every successful GA placement and PPO migration as well.
.\train_windows.ps1 -ShowSuccessfulDecisions $true

# Restore all legacy simulator diagnostics, including routing and placement maps.
.\train_windows.ps1 -ShowSimulatorDiagnostics $true

# Print Python bridge/PPO progress messages.
.\train_windows.ps1 -ShowPythonProgress $true

# Suppress even the per-trajectory summary.
.\train_windows.ps1 -ShowEpisodeSummary $false
```

The Bash equivalents are `--show-successful-decisions`,
`--simulator-diagnostics`, `--python-progress`, and `--no-episode-summary`.

### Checkpoint control

Training resumes and fine-tunes the selected model by default. Use a distinct
model/history pair to keep experiments separate:

```powershell
.\train_windows.ps1 `
  -ModelPath agents\models\experiment_a.pth `
  -ConvergencePath agents\results\experiment_a.json
```

Use `-ResetTraining` only when intentionally starting a fresh experiment. It
deletes exactly the selected model and convergence JSON before the server
starts; it does not delete the per-episode result files.

```powershell
.\train_windows.ps1 -ResetTraining
```

The Bash equivalents are `--model`, `--convergence`, and `--reset-training`.

### Protocol and learning unit

Step zero sends the original `devices`, `requests`, and `allModules` placement
schema to Python. Python calls the existing `GeneticAgent` unchanged and
returns its initial placement. The preprocessor starts on its client exactly as
in the existing GA setup, but is also an actor and may migrate later. Later
messages use `type: shared_step` and carry a bounded round-robin service batch
(32 actors per step by default; `-MaxActorsPerStep` / `--max-actors-per-step`).

Every service actor has:

- its module type and resource demand;
- its current node, home gateway, peer-service relationship, and client type;
- a variable candidate list with free CPU/RAM, utilization, node energy delta,
  estimated request latency, locality flags, and an exact feasibility mask;
- a local reward split into attributed host energy, request-specific delay,
  and migration/rejection penalty components.

The same candidate-scoring network is used for every service and every fog
node. Candidate masks and within-batch CPU reservations prevent the policy from
sampling destinations that cannot accept a service. The simulator still
performs a final capacity check.

Training episodes domain-randomize the number of gateways, client count,
mobile/static share, fog capacity, traffic, and selectivity from the episode
seed. A given seed is reproducible. For a controlled PPO/no-PPO comparison,
run the same seed and model in inference mode, setting `--max-migrations 0` for
the no-migration baseline; the GA initialization is identical in both runs.

Each new episode record also includes average end-to-end latency, separate
normal/critical latency, critical tasks emitted/on-time/missed, the critical
deadline success rate, pending/evaluated critical tasks, immediate mean local reward, and accepted/rejected
migrations. `meanTdTarget` is only a PPO value-learning diagnostic; it is not
the simulator reward.
