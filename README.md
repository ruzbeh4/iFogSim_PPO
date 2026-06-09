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
