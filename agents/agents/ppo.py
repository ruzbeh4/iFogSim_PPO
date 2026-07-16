"""
ppo.py – Trained Inference Agent.
Step 0: least‑loaded heuristic (same as ppo_old).
Step > 0: trained PPO model for migrations.
"""

import torch
import torch.nn as nn
import numpy as np
import os

from .base_agent import BasePlacementAgent

DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
BASE_DIR = os.path.dirname(os.path.dirname(__file__))
MODEL_PATH = os.path.join(BASE_DIR, "models", "ppo_model.pth")


class ActorCritic(nn.Module):
    def __init__(self, state_dim, num_modules, num_devices):
        super().__init__()
        self.feature_layer = nn.Sequential(
            nn.Linear(state_dim, 256), nn.ReLU(),
            nn.Linear(256, 128), nn.ReLU()
        )
        self.actor_module = nn.Linear(128, num_modules)
        self.actor_device = nn.Linear(128, num_devices)

    def forward(self, state):
        features = self.feature_layer(state)
        return self.actor_module(features), self.actor_device(features)


class PPOAgent(BasePlacementAgent):
    def __init__(self):
        super().__init__()
        self.max_candidates = 11
        self.max_modules = 300
        self.state_dim = (self.max_candidates * 2) + (self.max_modules * 2)

        self.policy = ActorCritic(self.state_dim, self.max_modules, self.max_candidates).to(DEVICE)
        if os.path.exists(MODEL_PATH):
            self.policy.load_state_dict(torch.load(MODEL_PATH, map_location=DEVICE, weights_only=True))
            self.policy.eval()
            print(f"[PPO Inference] Loaded model from {MODEL_PATH}")
        else:
            print(f"[WARNING] No model at {MODEL_PATH}. Random actions will be used.")

    # ----- Entry points -----

    def decide(self, state: dict) -> dict:
        """Static‑bridge fallback (not used in training/inference step loop)."""
        return self._heuristic_placement(state)

    def decide_step(self, state: dict) -> dict:
        """Step‑protocol entry point."""
        step = state.get("step", 0)
        done = state.get("done", False)

        if step == 0:
            print("[PPO Inference] Step 0: Initial placement using least-loaded heuristic.")
            return self._heuristic_placement_step(state)

        if done:
            return {"placements": [], "migrations": []}

        # ----- Step > 0 : PPO migration decision -----
        devices = state.get("devices", [])
        candidates = sorted([d for d in devices if d["level"] < 2], key=lambda x: x["id"])

        active_modules = sorted(
            [m for m in state.get("modules", [])
             if m.get("status") == "placed" and m.get("name") != "data_preprocessor"],
            key=lambda x: (x["requestId"], x["name"])
        )

        self.cached_dev_map = [d["id"] for d in candidates]
        self.cached_mod_map = [{"req": m["requestId"], "name": m["name"]} for m in active_modules]

        state_tensor = self._build_state_tensor(candidates, active_modules)

        with torch.no_grad():
            mod_logits, dev_logits = self.policy(state_tensor)
            mod_idx = torch.argmax(mod_logits).item()
            dev_idx = torch.argmax(dev_logits).item()

        migrations = []
        if mod_idx < len(self.cached_mod_map) and dev_idx < len(self.cached_dev_map):
            t_mod = self.cached_mod_map[mod_idx]
            t_dev_id = self.cached_dev_map[dev_idx]
            current_dev = next(
                (m["deviceId"] for m in active_modules
                 if m["requestId"] == t_mod["req"] and m["name"] == t_mod["name"]),
                -1
            )
            t_dev_info = next((d for d in candidates if d["id"] == t_dev_id), None)
            if t_dev_info and current_dev != t_dev_id:
                if (t_dev_info["availableMips"] - t_dev_info["currentLoad"]) >= 1000:
                    migrations.append({
                        "requestId": t_mod["req"],
                        "module": t_mod["name"],
                        "toDeviceId": t_dev_id
                    })

        return {"placements": [], "migrations": migrations}

    # ----- Heuristic helpers (exactly as in ppo_old) -----

    @staticmethod
    def _heuristic_placement(state: dict) -> dict:
        """Static placement (used only for fallback)."""
        candidates = BasePlacementAgent.candidate_devices(state)
        placement_map = {}
        for request in state.get("requests", []):
            per_req = {}
            for module in request.get("pendingModules", []):
                device = PPOAgent._least_loaded(module, candidates)
                if device is not None:
                    per_req[module["name"]] = device["id"]
            placement_map[request["requestId"]] = per_req
        return BasePlacementAgent.build_action(placement_map)

    @staticmethod
    def _heuristic_placement_step(state: dict) -> dict:
        """Step‑protocol initial placement: place all pending modules."""
        devices = state.get("devices", [])
        committed_mips = {d["id"]: 0.0 for d in devices}
        placements = []

        for module in state.get("modules", []):
            if module.get("status") != "pending":
                continue
            device = PPOAgent._least_loaded_raw(module, devices, committed_mips)
            if device is not None:
                placements.append({
                    "requestId": module["requestId"],
                    "module": module["name"],
                    "deviceId": device["id"],
                })
                committed_mips[device["id"]] += module["requiredMips"]

        return {"placements": placements, "migrations": []}

    @staticmethod
    def _least_loaded(module: dict, candidates: list) -> dict | None:
        for device in sorted(candidates,
                             key=lambda d: (-d["level"], -(BasePlacementAgent.available_mips(d)))):
            if (BasePlacementAgent.available_mips(device) >= module["requiredMips"]
                    and device["availableRam"] >= module["requiredRam"]):
                return device
        return None

    @staticmethod
    def _least_loaded_raw(module: dict, devices: list, committed_mips: dict) -> dict | None:
        eligible = [d for d in devices if d["level"] < 2]
        for device in sorted(eligible,
                             key=lambda d: (-d["level"], -(d["availableMips"] - committed_mips.get(d["id"], 0.0)))):
            free_mips = device["availableMips"] - committed_mips.get(device["id"], 0.0)
            if free_mips >= module["requiredMips"] and device["availableRam"] >= module["requiredRam"]:
                return device
        return None

    def _build_state_tensor(self, candidates, active_modules):
        state_arr = np.zeros(self.state_dim, dtype=np.float32)
        idx = 0
        for d in candidates[:self.max_candidates]:
            state_arr[idx] = d["availableMips"]
            state_arr[idx+1] = d["currentLoad"]
            idx += 2
        idx = self.max_candidates * 2
        for m in active_modules[:self.max_modules]:
            state_arr[idx] = m["deviceId"]
            state_arr[idx+1] = m["requiredMips"]
            idx += 2
        return torch.tensor(state_arr, dtype=torch.float32).to(DEVICE)