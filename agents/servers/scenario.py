"""TCP bridge server for scenario runs (heuristic / genetic / ppo)."""

import argparse
import json
import socketserver
import threading
import time

from agents import GeneticAgent, HeuristicAgent, PPOAgent
from utils.results_paths import make_run_dir

AGENTS = {
    "heuristic": HeuristicAgent,
    "genetic": GeneticAgent,
    "ppo": PPOAgent,
}

_agent = None
_agent_name = "heuristic"


class PlacementHandler(socketserver.StreamRequestHandler):
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
            import traceback
            traceback.print_exc()

    def _handle_placement(self, state: dict):
        print(
            f"[Server] step={state.get('step', '?')} | "
            f"devices={len(state.get('devices', []))} | "
            f"requests={len(state.get('requests', []))}"
        )
        action = _agent.decide(state)
        self.wfile.write((json.dumps(action) + "\n").encode("utf-8"))
        self.wfile.flush()
        for req_id, modules in action.get("placement", {}).items():
            for mod_name, dev_id in modules.items():
                print(f"  → req={req_id}  module={mod_name:<24}  device={_device_name(state, dev_id)}")

    def _handle_step(self, state: dict):
        step = state.get("step", "?")
        if step == 0 or state.get("done") or (isinstance(step, int) and step % 50 == 0):
            print(
                f"[Server] step={step:<5} simTime={state.get('simTime', 0.0):>7.1f}  "
                f"reward={state.get('reward', 0.0):>10.4f}  done={state.get('done', False)}"
            )
        decide_step = getattr(_agent, "decide_step", None)
        action = {"placements": [], "migrations": []} if decide_step is None else decide_step(state)
        self.wfile.write((json.dumps(action) + "\n").encode("utf-8"))
        self.wfile.flush()

    def _handle_results(self, results: dict):
        results["agent"] = _agent_name
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
        print("=" * 60 + "\n")

        run_dir = make_run_dir("single", f"scenario_{_agent_name}")
        filepath = str(run_dir / f"episode_{time.strftime('%H%M%S')}.json")
        with open(filepath, "w", encoding="utf-8") as handle:
            json.dump(results, handle, indent=2, ensure_ascii=False)
        print(f"[Server] Results saved → {filepath}")

        self.wfile.write((json.dumps({"status": "saved", "file": filepath}) + "\n").encode("utf-8"))
        self.wfile.flush()
        threading.Thread(target=self.server.shutdown, daemon=True).start()


class ThreadedTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


def _device_name(state: dict, device_id: int) -> str:
    for device in state.get("devices", []):
        if device["id"] == device_id:
            return device["name"]
    return str(device_id)


def main():
    parser = argparse.ArgumentParser(description="iFogSim2 scenario bridge server")
    parser.add_argument("--agent", default="heuristic", choices=list(AGENTS.keys()))
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=5555)
    args = parser.parse_args()

    global _agent, _agent_name
    _agent_name = args.agent
    _agent = AGENTS[args.agent]()

    print(f"[Server] Agent: {args.agent} ({type(_agent).__name__})")
    print(f"[Server] Listening: {args.host}:{args.port}")

    with ThreadedTCPServer((args.host, args.port), PlacementHandler) as server:
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("\n[Server] Shutting down.")


if __name__ == "__main__":
    main()
