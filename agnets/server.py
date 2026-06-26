"""
server.py
─────────
TCP bridge server – receives simulation state from the Java iFogSim2
simulator and returns placement decisions computed by a Python agent.
Also receives a final "results" message after the simulation ends,
saves it to disk as JSON, and cleanly closes the connection.

Protocol – two message types on the same port
──────────────────────────────────────────────
1. Placement request  (Java → Python)
   Single newline-terminated JSON line with keys: step, devices, requests, allModules
   Python replies with one JSON line: {"placement": {requestId: {module: deviceId}}}

2. Results report  (Java → Python, sent once after CloudSim.stopSimulation())
   Single newline-terminated JSON line with key "type" == "results"
   Python saves the payload to results/<agent>_<timestamp>.json and replies:
   {"status": "saved", "file": "<path>"}
   The socket is then closed on both sides.

Usage
─────
    python server.py                       # heuristic (default)
    python server.py --agent genetic
    python server.py --host 0.0.0.0 --port 5555

@author M-H-Boroumandnia
"""

import argparse
import json
import os
import socketserver
import threading
import time

from agents import HeuristicAgent, GeneticAgent


AGENTS = {
    "heuristic": HeuristicAgent,
    "genetic":   GeneticAgent,
}

_agent      = None
_agent_name = "heuristic"

RESULTS_DIR = os.path.join(os.path.dirname(__file__), "results")


class PlacementHandler(socketserver.StreamRequestHandler):
    """
    Handles one TCP connection from the Java bridge.
    Dispatches to _handle_placement() or _handle_results() based on message type.
    """
    def handle(self):
        try:
            raw = self.rfile.readline()
            if not raw:
                return

            payload = json.loads(raw.decode("utf-8").strip())

            if payload.get("type") == "results":
                self._handle_results(payload)
            else:
                self._handle_placement(payload)

        except json.JSONDecodeError as exc:
            print(f"[Server] JSON parse error: {exc}")
        except Exception as exc:
            print(f"[Server] Unexpected error: {exc}")
            import traceback; traceback.print_exc()


    def _handle_placement(self, state: dict):
        """Calls the active agent and writes its decision back to Java."""
        step        = state.get("step", "?")
        num_req     = len(state.get("requests",  []))
        num_devices = len(state.get("devices",   []))

        print(f"[Server] step={step} | devices={num_devices} | requests={num_req}")

        action = _agent.decide(state)

        response_line = json.dumps(action) + "\n"
        self.wfile.write(response_line.encode("utf-8"))
        self.wfile.flush()

        # Pretty-print the decision
        for req_id, modules in action.get("placement", {}).items():
            for mod_name, dev_id in modules.items():
                dev_name = _device_name(state, dev_id)
                print(f"  → req={req_id}  module={mod_name:<24}  device={dev_name}")

    # ── Results report ────────────────────────────────────────────────────────

    def _handle_results(self, results: dict):
        """
        Receives the final simulation results, prints a summary, saves to JSON,
        then sends an acknowledgement back to Java.
        The Java side closes its socket after reading the ack.
        """
        results["agent"] = _agent_name

        # latency_stats = _compute_latency(results)
        # results["latency"] = latency_stats

        print("\n" + "=" * 60)
        print("  SIMULATION RESULTS")
        print("=" * 60)
        print(f"  Agent            : {_agent_name}")
        print(f"  Simulation time  : {results.get('simulationTime', '?'):.1f} s")
        print(f"  Total energy     : {results.get('totalEnergy', 0):,.1f} J")
        print(f"  Cloud cost       : {results.get('cloudCost', 0):,.1f}")
        print(f"  Requests placed  : {results.get('numRequests', '?')}")

        # print("\n  Estimated E2E latency (ms):")
        # print(f"    avg  : {latency_stats['avg_ms']:>8.1f}")
        # print(f"    min  : {latency_stats['min_ms']:>8.1f}")
        # print(f"    max  : {latency_stats['max_ms']:>8.1f}")
        # print(f"    edge : {latency_stats['edge_pct']:>7.1f}%  placements on fog/IoT")

        print("\n  Energy per device:")
        for dev in results.get("energyPerDevice", []):
            bar_len = int(dev["energy"] / max(
                d["energy"] for d in results["energyPerDevice"]) * 30)
            bar = "█" * bar_len
            print(f"    {dev['name']:<14} L{dev['level']}  {bar}  {dev['energy']:>14,.1f} J")

        print("\n  Placement decisions:")
        for p in results.get("placements", []):
            print(f"    step={p['step']}  req={p['requestId']}  "
                #   f"{p['module']:<24} → {p['device']}  ({latency_stats['per_request'].get(str(p['requestId']), {}).get('e2e_ms', '?')} ms)")
                f"{p['module']:<24} → {p['device']}")
        print("=" * 60 + "\n")

        os.makedirs(RESULTS_DIR, exist_ok=True)
        timestamp = time.strftime("%Y%m%d_%H%M%S")
        filename  = f"{_agent_name}_{timestamp}.json"
        filepath  = os.path.join(RESULTS_DIR, filename)

        with open(filepath, "w", encoding="utf-8") as f:
            json.dump(results, f, indent=2, ensure_ascii=False)

        print(f"[Server] Results saved → {filepath}")

        ack = json.dumps({"status": "saved", "file": filepath}) + "\n"
        self.wfile.write(ack.encode("utf-8"))
        self.wfile.flush()

        threading.Thread(target=self.server.shutdown, daemon=True).start()

class ThreadedTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    """
    Each connection runs in its own daemon thread so placement rounds and the
    final results message never block each other.
    allow_reuse_address avoids "address already in use" on quick restarts.
    """
    allow_reuse_address = True
    daemon_threads      = True


def _compute_latency(results: dict) -> dict:
    """
    Estimates end-to-end control-loop latency from placement decisions.

    TimeKeeper's built-in loop tracking does not work in iFogSim2 microservices
    mode, so we derive latency analytically from the network topology:

      IoT_SENSOR → data_preprocessor (IoT, L2)
                 → smart_analyzer    (Python-placed)
                 → actuator_controller (Python-placed)
                 → data_preprocessor (IoT, L2)
                 → ACTUATOR

    Hop latencies match the constants in IndustrialIoTSimulation.java:
      IoT  → FogGW : 20 ms   (IOT_TO_GW_LATENCY)
      FogGW → Cloud : 100 ms  (GW_TO_CLOUD_LATENCY)
      sensor/actuator wire : 1 ms each side
    """
    # One-way latency from IoT (L2) to a module at the given level
    IOT_TO_LEVEL = {0: 120, 1: 20, 2: 0}   # cloud=120, fog=20, IoT=0

    # One-way latency between two modules based on their device levels.
    # Assumes same-level same-device = 0, otherwise route via parent.
    def hop(level_a: int, level_b: int) -> int:
        if level_a == level_b:
            return 0                        # co-located (best case)
        if {level_a, level_b} == {0, 1}:
            return 100                      # cloud ↔ fog gateway
        if {level_a, level_b} == {1, 2}:
            return 20                       # fog gateway ↔ IoT
        return 120                          # cloud ↔ IoT (two hops)

    # Build device-name → level lookup
    level_map = {d["name"]: d["level"] for d in results.get("energyPerDevice", [])}

    # Group placements by request ID, storing (device_name, level) per module
    from collections import defaultdict
    by_req: dict[str, dict[str, tuple]] = defaultdict(dict)
    for p in results.get("placements", []):
        lvl = level_map.get(p["device"], 0)
        by_req[str(p["requestId"])][p["module"]] = (p["device"], lvl)

    per_request = {}
    latencies   = []

    for req_id, modules in by_req.items():
        sa_name,  sa_lvl  = modules.get("smart_analyzer",      (None, 0))
        ac_name,  ac_lvl  = modules.get("actuator_controller", (None, 0))

        uplink   = IOT_TO_LEVEL.get(sa_lvl, 120)
        downlink = IOT_TO_LEVEL.get(ac_lvl, 120)

        if sa_name == ac_name:
            inter = 0           # co-located, no network hop
        elif sa_lvl == 1 and ac_lvl == 1:
            inter = 200         # different fog gateways → via cloud
        elif {sa_lvl, ac_lvl} == {0, 1}:
            inter = 100         # cloud ↔ fog
        else:
            inter = 0           # both on cloud (same datacenter)

        e2e = 2 + uplink + inter + downlink 

        per_request[req_id] = {
            "smart_analyzer_device":      sa_name,
            "actuator_controller_device": ac_name,
            "e2e_ms":                     e2e,
        }
        latencies.append(e2e)

    if not latencies:
        return {"avg_ms": 0, "min_ms": 0, "max_ms": 0,
                "edge_pct": 0, "per_request": {}}

    total_placements = sum(len(m) for m in by_req.values())
    edge_placements  = sum(
        1 for m in by_req.values() for _, lvl in m.values() if lvl > 0
    )

    return {
        "avg_ms":      round(sum(latencies) / len(latencies), 2),
        "min_ms":      min(latencies),
        "max_ms":      max(latencies),
        "edge_pct":    round(edge_placements / total_placements * 100, 1) if total_placements else 0,
        "per_request": per_request,
    }


def _device_name(state: dict, device_id: int) -> str:
    for d in state.get("devices", []):
        if d["id"] == device_id:
            return d["name"]
    return str(device_id)


def main():
    parser = argparse.ArgumentParser(
        description="iFogSim2 Python placement-agent bridge server")
    parser.add_argument(
        "--agent", default="heuristic", choices=list(AGENTS.keys()),
        help="Placement agent to use (default: heuristic)")
    parser.add_argument(
        "--host", default="localhost",
        help="Interface to bind (default: localhost)")
    parser.add_argument(
        "--port", type=int, default=5555,
        help="TCP port to listen on (default: 5555)")
    args = parser.parse_args()

    global _agent, _agent_name
    _agent_name = args.agent
    _agent      = AGENTS[args.agent]()

    print(f"[Server] Agent      : {args.agent} ({type(_agent).__name__})")
    print(f"[Server] Listening  : {args.host}:{args.port}")
    print(f"[Server] Results dir: {RESULTS_DIR}")
    print(f"[Server] Protocol   : newline-terminated JSON over TCP")
    print(f"[Server] Waiting for iFogSim2...\n")

    with ThreadedTCPServer((args.host, args.port), PlacementHandler) as server:
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("\n[Server] Shutting down.")


if __name__ == "__main__":
    main()
