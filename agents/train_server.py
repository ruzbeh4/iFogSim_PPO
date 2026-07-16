"""
train_server.py – Persistent TCP server for DRL training.
Stays alive after each episode; calls agent.on_episode_end().
"""

import argparse
import json
import os
import socketserver

from agents import HeuristicAgent, GeneticAgent, PPOAgent
from agents.ppo_train import PPOTrainAgent

AGENTS = {
    "heuristic": HeuristicAgent,
    "genetic": GeneticAgent,
    "ppo": PPOAgent,
    "ppo_train": PPOTrainAgent,
}

_agent = None
_agent_name = "ppo_train"
RESULTS_DIR = os.path.join(os.path.dirname(__file__), "results")


class TrainPlacementHandler(socketserver.StreamRequestHandler):
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
        except Exception as e:
            print(f"[TrainServer] Error: {e}")
            import traceback
            traceback.print_exc()

    def _handle_placement(self, state):
        action = _agent.decide(state)
        self.wfile.write((json.dumps(action) + "\n").encode("utf-8"))
        self.wfile.flush()

    def _handle_step(self, state):
        decide_step = getattr(_agent, "decide_step", None)
        if decide_step is None:
            action = {"placements": [], "migrations": []}
        else:
            action = decide_step(state)
        self.wfile.write((json.dumps(action) + "\n").encode("utf-8"))
        self.wfile.flush()

    def _handle_results(self, results):
        if hasattr(_agent, "on_episode_end"):
            _agent.on_episode_end(results)
        ack = json.dumps({"status": "saved", "file": "training_in_progress"}) + "\n"
        self.wfile.write(ack.encode("utf-8"))
        self.wfile.flush()
        print("[TrainServer] Episode completed. Awaiting next Java launch...")


class ThreadedTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--agent", default="ppo_train", choices=list(AGENTS.keys()))
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=5555)
    args = parser.parse_args()

    global _agent, _agent_name
    _agent_name = args.agent
    _agent = AGENTS[args.agent]()

    os.makedirs(RESULTS_DIR, exist_ok=True)
    print(f"[TrainServer] Agent: {args.agent}")
    print(f"[TrainServer] Bound to port {args.port}. Awaiting simulation episodes...")

    with ThreadedTCPServer((args.host, args.port), TrainPlacementHandler) as server:
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("\n[TrainServer] Shutting down.")


if __name__ == "__main__":
    main()