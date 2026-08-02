"""Bridge server for placement/migration agent comparison runs.

Supported agents (placement + migration):
  heuristic            Heuristic placement only (no migrations)
  genetic              Genetic placement only (no migrations)
  genetic_heuristic    Genetic placement + Heuristic migrations   (GA + Heuristic)
  ppo                  Genetic placement + PPO migrations         (GA + PPO)
  heuristic_heuristic  Heuristic placement + Heuristic migrations
  heuristic_ppo        Heuristic placement + PPO migrations
  heur_v2_heuristic    Heuristic-v2 placement + Heuristic migrations
  heur_v2_ppo          Heuristic-v2 placement + PPO migrations
"""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import os
import random
import socketserver
import threading
import time

import numpy as np

from agents import GeneticAgent, HeuristicAgent
from agents.shared_ppo import SharedPPOAgent
from utils.results_paths import latest_model_path, make_run_dir

AGENT_CHOICES = (
    "heuristic",
    "genetic",
    "genetic_heuristic",
    "ppo",
    "heuristic_heuristic",
    "heuristic_ppo",
    "heur_v2_heuristic",
    "heur_v2_ppo",
)
PPO_MIGRATION_AGENTS = frozenset({"ppo", "heuristic_ppo", "heur_v2_ppo"})
HEURISTIC_MIGRATION_AGENTS = frozenset({
    "genetic_heuristic", "heuristic_heuristic", "heur_v2_heuristic",
})
HEURISTIC_PLACEMENT_AGENTS = frozenset({"heuristic", "heuristic_heuristic", "heuristic_ppo"})
# Heuristic-v2: intentionally weaker initial placement for migration repair experiments.
HEUR_V2_PLACEMENT_AGENTS = frozenset({"heur_v2_heuristic", "heur_v2_ppo"})
GENETIC_PLACEMENT_AGENTS = frozenset({"genetic", "genetic_heuristic"})


class CompareServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


class CompareHandler(socketserver.StreamRequestHandler):
    def handle(self):
        raw = self.rfile.readline()
        if not raw:
            return
        try:
            payload = json.loads(raw.decode("utf-8"))
            with self.server.agent_lock:
                response = self._dispatch(payload)
            self.wfile.write((json.dumps(response, separators=(",", ":")) + "\n").encode("utf-8"))
            self.wfile.flush()
        except Exception as exc:
            import traceback
            traceback.print_exc()
            error = {"error": f"{type(exc).__name__}: {exc}"}
            self.wfile.write((json.dumps(error) + "\n").encode("utf-8"))
            self.wfile.flush()

    def _dispatch(self, payload):
        message_type = payload.get("type")
        agent_name = self.server.agent_name

        if message_type == "shared_initial":
            return self._initial_placement(payload)
        if message_type == "shared_step":
            return self._step_migrations(payload)
        if message_type == "results":
            payload = dict(payload)
            payload["agent"] = agent_name
            if agent_name in PPO_MIGRATION_AGENTS:
                self.server.ppo.on_episode_end(payload)
            self._save_episode(payload)
            episode = getattr(self.server.ppo, "episode", payload.get("episodeSeed"))
            return {"status": "saved", "episode": episode, "agent": agent_name}
        raise ValueError(f"Unsupported message type: {message_type!r}")

    def _initial_placement(self, state: dict) -> dict:
        seed = int(state.get("episodeSeed", 1))
        random.seed(seed)
        np.random.seed(seed & 0xFFFFFFFF)
        ga_state = {key: state[key] for key in ("step", "devices", "requests", "allModules")}
        agent_name = self.server.agent_name

        if agent_name in HEUR_V2_PLACEMENT_AGENTS:
            decision = self.server.heur_v2.decide(ga_state)
        elif agent_name in HEURISTIC_PLACEMENT_AGENTS:
            decision = self.server.heuristic.decide(ga_state)
        elif agent_name in GENETIC_PLACEMENT_AGENTS:
            with contextlib.redirect_stdout(io.StringIO()):
                decision = self.server.genetic.decide(ga_state)
        else:
            # ppo / GA + PPO: SharedPPO still uses Genetic for initial placement.
            with contextlib.redirect_stdout(io.StringIO()):
                return self.server.ppo.decide_initial(state)

        placements = []
        for request_id, modules in decision.get("placement", {}).items():
            for module, device_id in modules.items():
                placements.append({
                    "requestId": int(request_id),
                    "module": module,
                    "deviceId": int(device_id),
                })
        return {"placements": placements, "migrations": []}

    def _step_migrations(self, state: dict) -> dict:
        agent_name = self.server.agent_name
        if agent_name in PPO_MIGRATION_AGENTS:
            return self.server.ppo.decide_step(state)
        if agent_name in HEURISTIC_MIGRATION_AGENTS:
            return self.server.heuristic.decide_step(
                state, max_migrations=self.server.max_migrations,
            )
        # Placement-only baselines: no online migrations.
        return {"actions": []}

    def _save_episode(self, payload: dict) -> None:
        os.makedirs(self.server.results_dir, exist_ok=True)
        seed = payload.get("episodeSeed", "unknown")
        filename = f"episode_{seed}_{time.strftime('%Y%m%d_%H%M%S')}.json"
        path = os.path.join(self.server.results_dir, filename)
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, indent=2)
        if self.server.progress:
            print(f"[compare] saved {path}")


def main():
    parser = argparse.ArgumentParser(description="Fair agent comparison bridge server")
    parser.add_argument("--agent", choices=AGENT_CHOICES, required=True)
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=5555)
    parser.add_argument("--model", default=None)
    parser.add_argument("--results-dir", default=None)
    parser.add_argument("--run-name", default=None)
    parser.add_argument("--max-migrations", type=int, default=4)
    parser.add_argument("--progress", action="store_true")
    parser.add_argument(
        "--placement-init",
        choices=("genetic", "heuristic", "bad_heuristic"),
        default=None,
        help="Initial placement used by SharedPPO (defaults: genetic for ppo, bad_heuristic for heuristic_ppo)",
    )
    args = parser.parse_args()

    if args.results_dir:
        results_dir = args.results_dir
    else:
        run_name = args.run_name or time.strftime("compare_%H%M%S")
        results_dir = str(make_run_dir("compare", run_name) / args.agent)

    model_path = args.model
    if not model_path:
        latest = latest_model_path("shared_ppo")
        model_path = str(latest) if latest is not None else None

    if args.placement_init is not None:
        placement_init = args.placement_init
    elif args.agent in ("heuristic_ppo", "heur_v2_ppo"):
        placement_init = "bad_heuristic"
    else:
        placement_init = "genetic"

    ppo = SharedPPOAgent(
        model_path=model_path,
        convergence_path=os.path.join(results_dir, "convergence.json"),
        training=False,
        max_migrations_per_step=args.max_migrations,
        verbose=args.progress,
        placement_init=placement_init,
    )

    with CompareServer((args.host, args.port), CompareHandler) as server:
        server.agent_name = args.agent
        server.results_dir = results_dir
        server.max_migrations = args.max_migrations
        server.heuristic = HeuristicAgent(bad_placement=False)
        server.heur_v2 = HeuristicAgent(bad_placement=True)
        server.genetic = GeneticAgent()
        server.ppo = ppo
        server.agent_lock = threading.Lock()
        server.progress = args.progress
        print(f"[compare] agent={args.agent} port={args.port} results={results_dir}")
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("\n[compare] stopping")


if __name__ == "__main__":
    main()
