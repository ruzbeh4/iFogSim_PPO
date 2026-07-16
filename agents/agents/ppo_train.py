"""
ppo_train.py – Active PPO training agent with debug logging.
Step 0: least‑loaded heuristic.
Step > 0: PPO network with on‑episode updates.
"""

import torch
import torch.nn as nn
import torch.optim as optim
from torch.distributions import Categorical
import numpy as np
import json
import os

from .base_agent import BasePlacementAgent

BASE_DIR = os.path.dirname(os.path.dirname(__file__))
MODELS_DIR = os.path.join(BASE_DIR, "models")
RESULTS_DIR = os.path.join(BASE_DIR, "results")

LR = 0.0003
GAMMA = 0.99
EPS_CLIP = 0.2
K_EPOCHS = 4
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")


class ActorCritic(nn.Module):
    def __init__(self, state_dim, num_modules, num_devices):
        super().__init__()
        self.feature_layer = nn.Sequential(
            nn.Linear(state_dim, 256), nn.ReLU(),
            nn.Linear(256, 128), nn.ReLU()
        )
        self.actor_module = nn.Linear(128, num_modules)
        self.actor_device = nn.Linear(128, num_devices)
        self.critic = nn.Linear(128, 1)

    def act(self, state):
        features = self.feature_layer(state)
        dist_mod = Categorical(logits=self.actor_module(features))
        dist_dev = Categorical(logits=self.actor_device(features))
        a_mod = dist_mod.sample()
        a_dev = dist_dev.sample()
        logprob = dist_mod.log_prob(a_mod) + dist_dev.log_prob(a_dev)
        return a_mod.item(), a_dev.item(), logprob

    def evaluate(self, state, a_mod, a_dev):
        features = self.feature_layer(state)
        dist_mod = Categorical(logits=self.actor_module(features))
        dist_dev = Categorical(logits=self.actor_device(features))
        logprobs = dist_mod.log_prob(a_mod) + dist_dev.log_prob(a_dev)
        entropy = dist_mod.entropy() + dist_dev.entropy()
        values = self.critic(features)
        return logprobs, values, entropy


class PPOTrainAgent(BasePlacementAgent):
    def __init__(self):
        super().__init__()
        self.max_candidates = 11
        self.max_modules = 300
        self.state_dim = (self.max_candidates * 2) + (self.max_modules * 2)

        self.policy = ActorCritic(self.state_dim, self.max_modules, self.max_candidates).to(DEVICE)
        self.optimizer = optim.Adam(self.policy.parameters(), lr=LR)
        self.policy_old = ActorCritic(self.state_dim, self.max_modules, self.max_candidates).to(DEVICE)
        self.policy_old.load_state_dict(self.policy.state_dict())

        # Buffers for one episode
        self.states = []
        self.actions = []
        self.logprobs = []
        self.rewards = []
        self.is_terminals = []

        # Pending transition (state, action, logprob) from previous step
        self.pending_state = None
        self.pending_action = None
        self.pending_logprob = None

        self.episode_count = 0
        self.convergence_data = []

        # Debug counters
        self.step_counter = 0
        self.migration_attempts = 0
        self.successful_migrations = 0

    # ----- Entry points -----

    def decide(self, state: dict) -> dict:
        """Static‑bridge fallback."""
        return self._heuristic_placement(state)

    def decide_step(self, state: dict) -> dict:
        step = state.get("step", 0)
        done = state.get("done", False)
        self.step_counter += 1

        # ----- Step 0: heuristic initial placement -----
        if step == 0:
            print(f"[PPO Train DEBUG] Step 0: Initial placement using heuristic.")
            self._clear_buffers()
            return self._heuristic_placement_step(state)

        # ----- Step > 0: PPO interaction -----
        raw_reward = state.get("reward", 0.0)
        clipped_reward = np.clip(raw_reward, -200.0, 10.0)

        # If we have a pending transition from the previous step, attach this reward to it
        if self.pending_state is not None:
            self.states.append(self.pending_state)
            self.actions.append(self.pending_action)
            self.logprobs.append(self.pending_logprob)
            self.rewards.append(clipped_reward)
            self.is_terminals.append(done)
            # Clear pending
            self.pending_state = None
            self.pending_action = None
            self.pending_logprob = None

        if done:
            print(f"[PPO Train DEBUG] Episode ends at step {step}. Updating PPO...")
            self._update_ppo()
            return {"placements": [], "migrations": []}

        # --- Not done: select an action for the current step ---
        devices = state.get("devices", [])
        candidates = sorted([d for d in devices if d["level"] < 2], key=lambda x: x["id"])
        active_modules = sorted(
            [m for m in state.get("modules", [])
             if m.get("status") == "placed" and m.get("name") != "data_preprocessor"],
            key=lambda x: (x["requestId"], x["name"])
        )

        # Cache for later
        self.cached_dev_map = [d["id"] for d in candidates]
        self.cached_mod_map = [{"req": m["requestId"], "name": m["name"]} for m in active_modules]

        print(f"[PPO Train DEBUG] Step {step}: candidates={len(self.cached_dev_map)}, modules={len(self.cached_mod_map)}")

        state_tensor = self._build_state_tensor(candidates, active_modules)

        with torch.no_grad():
            mod_idx, dev_idx, logprob = self.policy_old.act(state_tensor)

        # Store as pending transition (reward will be attached next step)
        self.pending_state = state_tensor
        self.pending_action = (mod_idx, dev_idx)
        self.pending_logprob = logprob

        print(f"[PPO Train DEBUG] Action chosen: mod_idx={mod_idx}, dev_idx={dev_idx}")

        # --- Validate action and build migration ---
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
            if t_dev_info:
                free_mips = t_dev_info["availableMips"] - t_dev_info["currentLoad"]
                if current_dev != t_dev_id:
                    if free_mips >= 1000:  # required MIPS for the module
                        migrations.append({
                            "requestId": t_mod["req"],
                            "module": t_mod["name"],
                            "toDeviceId": t_dev_id
                        })
                        self.successful_migrations += 1
                        print(f"[PPO Train DEBUG] *** MIGRATION SUCCESS: {t_mod['name']} to device {t_dev_id} ***")
                    else:
                        print(f"[PPO Train DEBUG] Migration rejected: insufficient MIPS on target device {t_dev_id} (free={free_mips})")
                else:
                    print(f"[PPO Train DEBUG] Migration rejected: module already on device {t_dev_id}")
            else:
                print(f"[PPO Train DEBUG] Migration rejected: target device {t_dev_id} not found")
        else:
            print(f"[PPO Train DEBUG] Action indices out of range: mod_idx={mod_idx} (max={len(self.cached_mod_map)-1}), dev_idx={dev_idx} (max={len(self.cached_dev_map)-1})")

        self.migration_attempts += 1
        if self.step_counter % 10 == 0:
            print(f"[PPO Train STATS] Attempts={self.migration_attempts}, Successes={self.successful_migrations}")

        return {"placements": [], "migrations": migrations}

    # ----- PPO update -----

    def _update_ppo(self):
        # Ensure we have data
        if len(self.states) == 0:
            print("[PPO Train DEBUG] No states collected, skipping update.")
            self._clear_buffers()
            return

        # Discounted rewards
        rewards = []
        disc = 0
        for r, term in zip(reversed(self.rewards), reversed(self.is_terminals)):
            if term:
                disc = 0
            disc = r + GAMMA * disc
            rewards.insert(0, disc)
        rewards = torch.tensor(rewards, dtype=torch.float32).to(DEVICE)
        rewards = (rewards - rewards.mean()) / (rewards.std() + 1e-7)

        old_states = torch.squeeze(torch.stack(self.states)).detach().to(DEVICE)
        old_a_mod = torch.tensor([a[0] for a in self.actions]).to(DEVICE)
        old_a_dev = torch.tensor([a[1] for a in self.actions]).to(DEVICE)
        old_logprobs = torch.squeeze(torch.stack(self.logprobs)).detach().to(DEVICE)

        for _ in range(K_EPOCHS):
            logprobs, values, entropy = self.policy.evaluate(old_states, old_a_mod, old_a_dev)
            values = torch.squeeze(values)
            ratios = torch.exp(logprobs - old_logprobs.detach())
            advantages = rewards - values.detach()
            surr1 = ratios * advantages
            surr2 = torch.clamp(ratios, 1 - EPS_CLIP, 1 + EPS_CLIP) * advantages
            loss = -torch.min(surr1, surr2) + 0.5 * nn.MSELoss()(values, rewards) - 0.01 * entropy

            self.optimizer.zero_grad()
            loss.mean().backward()
            self.optimizer.step()

        self.policy_old.load_state_dict(self.policy.state_dict())
        self._clear_buffers()
        print("[PPO Train DEBUG] PPO update completed.")

    def _clear_buffers(self):
        self.states.clear()
        self.actions.clear()
        self.logprobs.clear()
        self.rewards.clear()
        self.is_terminals.clear()
        self.pending_state = None
        self.pending_action = None
        self.pending_logprob = None
        # Reset stats for next episode
        self.step_counter = 0
        self.migration_attempts = 0
        self.successful_migrations = 0

    def on_episode_end(self, results: dict):
        self.episode_count += 1
        step_rewards = [s["reward"] for s in results.get("stepRewards", [])]
        total = sum(step_rewards)
        self.convergence_data.append({"episode": self.episode_count, "total_reward": total})
        print(f"[PPO Train] Episode {self.episode_count} | Total Reward: {total:.2f} | Migrations attempted: {self.migration_attempts}, succeeded: {self.successful_migrations}")

        os.makedirs(MODELS_DIR, exist_ok=True)
        os.makedirs(RESULTS_DIR, exist_ok=True)
        torch.save(self.policy.state_dict(), os.path.join(MODELS_DIR, "ppo_model.pth"))
        with open(os.path.join(RESULTS_DIR, "convergence.json"), "w") as f:
            json.dump(self.convergence_data, f, indent=2)

    # ----- Heuristic helpers (fixed variable bug) -----

    @staticmethod
    def _heuristic_placement(state: dict) -> dict:
        candidates = BasePlacementAgent.candidate_devices(state)
        placement_map = {}
        for request in state.get("requests", []):
            per_req = {}
            for module in request.get("pendingModules", []):
                device = PPOTrainAgent._least_loaded(module, candidates)
                if device is not None:
                    per_req[module["name"]] = device["id"]
            placement_map[request["requestId"]] = per_req
        return BasePlacementAgent.build_action(placement_map)

    @staticmethod
    def _heuristic_placement_step(state: dict) -> dict:
        devices = state.get("devices", [])
        committed_mips = {d["id"]: 0.0 for d in devices}
        placements = []
        for module in state.get("modules", []):
            if module.get("status") != "pending":
                continue
            device = PPOTrainAgent._least_loaded_raw(module, devices, committed_mips)
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
            # FIXED: use 'device' instead of 'd'
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