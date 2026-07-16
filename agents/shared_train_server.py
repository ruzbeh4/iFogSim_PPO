"""Persistent bridge server for shared-service PPO training."""

import argparse
import contextlib
import io
import json
import os
import socketserver
import threading
import time

from agents.shared_ppo import SharedPPOAgent


class SharedTrainingServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


class SharedTrainingHandler(socketserver.StreamRequestHandler):
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
        if message_type == "shared_initial":
            if self.server.progress:
                print(f"[SharedServer] episodeSeed={payload.get('episodeSeed')} genetic initial placement")
                return self.server.agent.decide_initial(payload)
            # GeneticAgent intentionally remains unchanged; hide its one-line
            # fitness print during normal compact training output.
            with contextlib.redirect_stdout(io.StringIO()):
                return self.server.agent.decide_initial(payload)
        if message_type == "shared_step":
            if self.server.progress and (payload.get("done") or int(payload.get("step", 0)) % 20 == 0):
                print(f"[SharedServer] step={payload.get('step')} actors={len(payload.get('actors', []))} done={payload.get('done')}")
            return self.server.agent.decide_step(payload)
        if message_type == "results":
            self.server.agent.on_episode_end(payload)
            self._save_episode(payload)
            return {"status": "saved", "episode": self.server.agent.episode}
        raise ValueError(f"Unsupported message type: {message_type!r}")

    @staticmethod
    def _save_episode(payload):
        results_dir = os.path.join(os.path.dirname(__file__), "results", "shared_ppo")
        os.makedirs(results_dir, exist_ok=True)
        seed = payload.get("episodeSeed", "unknown")
        filename = f"episode_{seed}_{time.strftime('%Y%m%d_%H%M%S')}.json"
        with open(os.path.join(results_dir, filename), "w", encoding="utf-8") as handle:
            json.dump(payload, handle, indent=2)


def main():
    parser = argparse.ArgumentParser(description="Shared-service PPO training server")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=5555)
    parser.add_argument("--model", default=None)
    parser.add_argument("--convergence", default=None,
                        help="Path for this experiment's convergence history JSON")
    parser.add_argument("--max-migrations", type=int, default=2)
    parser.add_argument("--inference", action="store_true")
    parser.add_argument("--progress", action="store_true",
                        help="Print bridge/PPO progress in addition to trajectory summaries")
    args = parser.parse_args()

    agent = SharedPPOAgent(
        model_path=args.model,
        convergence_path=args.convergence,
        training=not args.inference,
        max_migrations_per_step=args.max_migrations,
        verbose=args.progress,
    )
    with SharedTrainingServer((args.host, args.port), SharedTrainingHandler) as server:
        server.agent = agent
        server.agent_lock = threading.Lock()
        server.progress = args.progress
        if args.progress:
            print(f"[SharedServer] listening on {args.host}:{args.port}; device={DEVICE_NAME(agent)}")
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("\n[SharedServer] stopping")


def DEVICE_NAME(agent):
    return str(next(agent.policy.parameters()).device)


if __name__ == "__main__":
    main()
