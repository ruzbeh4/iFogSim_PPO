"""
server.py
─────────
TCP bridge server – receives simulation state from the Java iFogSim2
simulator and returns placement decisions computed by a Python agent.
Also receives a final "results" message after the simulation ends,
saves it to disk as JSON, and cleanly closes the connection.

Protocol – three message types on the same port
──────────────────────────────────────────────
1. Placement request  (Java → Python, STATIC+SEQUENTIAL scenarios — heuristic/genetic)
   Single newline-terminated JSON line with keys: step, devices, requests, allModules
   Python replies with one JSON line: {"placement": {requestId: {module: deviceId}}}

2. Step request  (Java → Python, PERIODIC scenario — PPO, sent every
   MicroservicePlacementConfig.PLACEMENT_INTERVAL for the whole simulation,
   not just once)
   Single newline-terminated JSON line with key "type" == "step", keys:
   step, simTime, done, reward, devices, modules
   Python replies with one JSON line:
   {"placements": [{"requestId":.., "module":.., "deviceId":..}, ...],
    "migrations":  [{"requestId":.., "module":.., "toDeviceId":..}, ...]}

3. Results report  (Java → Python, sent once after CloudSim.stopSimulation())
   Single newline-terminated JSON line with key "type" == "results"
   Python saves the payload to results/<agent>_<timestamp>.json and replies:
   {"status": "saved", "file": "<path>"}
   The socket is then closed on both sides.

Usage
─────
    python server.py                       # heuristic (default)
    python server.py --agent genetic
    python server.py --agent ppo
    python server.py --host 0.0.0.0 --port 5555

@author M-H-Boroumandnia
"""

import argparse
import json
import os
import socketserver
import threading
import time

from agents import HeuristicAgent, GeneticAgent, PPOAgent


AGENTS = {
    "heuristic": HeuristicAgent,
    "genetic":   GeneticAgent,
    "ppo":       PPOAgent,
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
            elif payload.get("type") == "step":
                self._handle_step(payload)
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

    # ── PPO step request ──────────────────────────────────────────────────────

    def _handle_step(self, state: dict):
        """
        One Gym-style step() exchange: Java sends the current state plus the
        reward for the PREVIOUS action; Python replies with the next action
        (new placements + migrations). Repeats every PLACEMENT_INTERVAL for
        the whole simulation, so this prints sparsely to avoid flooding stdout.
        """
        step   = state.get("step", "?")
        sim_t  = state.get("simTime", 0.0)
        reward = state.get("reward", 0.0)
        done   = state.get("done", False)

        if step == 0 or done or (isinstance(step, int) and step % 50 == 0):
            print(f"[Server] step={step:<5} simTime={sim_t:>7.1f}  "
                  f"reward={reward:>10.4f}  done={done}")

        decide_step = getattr(_agent, "decide_step", None)
        if decide_step is None:
            # Agent wasn't built for the step protocol (e.g. heuristic/genetic
            # accidentally pointed at the PPO bridge) — reply with a safe no-op.
            action = {"placements": [], "migrations": []}
        else:
            action = decide_step(state)

        response_line = json.dumps(action) + "\n"
        self.wfile.write(response_line.encode("utf-8"))
        self.wfile.flush()

        for p in action.get("placements", []):
            print(f"  → place    req={p['requestId']}  {p['module']:<24} → device {p['deviceId']}")
        for m in action.get("migrations", []):
            print(f"  → migrate  req={m['requestId']}  {m['module']:<24} → device {m['toDeviceId']}")

    # ── Results report ────────────────────────────────────────────────────────

    def _handle_results(self, results: dict):
        """
        Receives the final simulation results, prints a summary, saves to JSON,
        then sends an acknowledgement back to Java.
        The Java side closes its socket after reading the ack.
        """
        results["agent"] = _agent_name
        loop_delay   = results.get("loopDelay")
        loop_samples = results.get("loopSampleCount", 0)
        cpu_delays   = results.get("tupleCpuDelays", {})

        print("\n" + "=" * 60)
        print("  SIMULATION RESULTS")
        print("=" * 60)
        print(f"  Agent            : {_agent_name}")
        print(f"  Simulation time  : {results.get('simulationTime', '?'):.1f} s")
        print(f"  Total energy     : {results.get('totalEnergy', 0):,.1f} J")
        print(f"  Cloud cost       : {results.get('cloudCost', 0):,.1f}")
        print(f"  Requests placed  : {results.get('numRequests', '?')}")
        if "migrationCount" in results:
            print(f"  Service migrations: {results['migrationCount']}")

        if loop_delay is not None:
            print(f"  E2E loop delay   : {loop_delay:,.2f} ms  "
                  f"(TimeKeeper, avg over {loop_samples} completed loops)")
        else:
            print(f"  E2E loop delay   : n/a")

        if cpu_delays:
            print("\n  Tuple CPU execution delay (ms):")
            for tuple_type, delay_ms in cpu_delays.items():
                print(f"    {tuple_type:<24} {delay_ms:>10.4f}")

        print("\n  Energy per device:")
        for dev in results.get("energyPerDevice", []):
            bar_len = int(dev["energy"] / max(
                d["energy"] for d in results["energyPerDevice"]) * 30)
            bar = "█" * bar_len
            print(f"    {dev['name']:<14} L{dev['level']}  {bar}  {dev['energy']:>14,.1f} J")

        print("\n  Placement decisions:")
        for p in results.get("placements", []):
            print(f"    step={p['step']}  req={p['requestId']}  "
                f"{p['module']:<24} → {p['device']}")

        migrations = results.get("migrations", [])
        if migrations:
            print("\n  Migrations:")
            for m in migrations:
                print(f"    step={m['step']}  req={m['requestId']}  "
                      f"{m['module']:<24} {m['fromDevice']} → {m['toDevice']}")

        step_rewards = results.get("stepRewards", [])
        if step_rewards:
            rewards = [s["reward"] for s in step_rewards]
            print(f"\n  PPO step rewards ({len(rewards)} steps, for the convergence-curve plot):")
            print(f"    first : {rewards[0]:>10.4f}")
            print(f"    last  : {rewards[-1]:>10.4f}")
            print(f"    mean  : {sum(rewards) / len(rewards):>10.4f}")

        mobility = results.get("mobility", {})
        if mobility:
            print(f"\n  Mobility traces (seeded random walk, {len(mobility)} mobile devices):")
            for name, trace in list(mobility.items())[:5]:
                start = trace[0]
                end   = trace[-1]
                dist  = ((end[0] - start[0]) ** 2 + (end[1] - start[1]) ** 2) ** 0.5
                print(f"    {name:<24} {len(trace)} steps, net displacement {dist:.1f} m")
            if len(mobility) > 5:
                print(f"    ... and {len(mobility) - 5} more (full traces saved to JSON)")

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
