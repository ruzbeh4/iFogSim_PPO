"""
ppo.py
──────
Trained PPO Agent for Microservice Migration.

Integrates PyTorch to learn migration policies based on live simulator feedback.
- Step 0: Uses a heuristic baseline for initial placement.
- Step > 0: PPO neural network decides if a single module should be migrated
  to combat latency and energy spikes.

@author M-H-Boroumandnia
"""

import torch
import torch.nn as nn
import torch.optim as optim
from torch.distributions import Categorical
import numpy as np

from .base_agent import BasePlacementAgent

# --- PPO Hyperparameters ---
LR = 0.0003
GAMMA = 0.99
EPS_CLIP = 0.2
K_EPOCHS = 4

# Device configuration
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")

class Memory:
    def __init__(self):
        self.states = []
        self.actions = []
        self.logprobs = []
        self.rewards = []
        self.is_terminals = []

    def clear(self):
        del self.states[:]
        del self.actions[:]
        del self.logprobs[:]
        del self.rewards[:]
        del self.is_terminals[:]

class ActorCritic(nn.Module):
    def __init__(self, state_dim, num_modules, num_devices):
        super(ActorCritic, self).__init__()

        # Shared features
        self.feature_layer = nn.Sequential(
            nn.Linear(state_dim, 256),
            nn.Tanh(),
            nn.Linear(256, 128),
            nn.Tanh()
        )

        # Actor: Decides WHICH module to move (module_logits) and WHERE (device_logits)
        self.actor_module = nn.Linear(128, num_modules)
        self.actor_device = nn.Linear(128, num_devices)

        # Critic: Estimates the Value of the state
        self.critic = nn.Linear(128, 1)

    def forward(self):
        raise NotImplementedError

    def act(self, state):
        features = self.feature_layer(state)

        module_logits = self.actor_module(features)
        device_logits = self.actor_device(features)

        dist_module = Categorical(logits=module_logits)
        dist_device = Categorical(logits=device_logits)

        action_module = dist_module.sample()
        action_device = dist_device.sample()

        # Joint log probability
        action_logprob = dist_module.log_prob(action_module) + dist_device.log_prob(action_device)

        return action_module.item(), action_device.item(), action_logprob

    def evaluate(self, state, action_module, action_device):
        features = self.feature_layer(state)

        dist_module = Categorical(logits=self.actor_module(features))
        dist_device = Categorical(logits=self.actor_device(features))

        action_logprobs = dist_module.log_prob(action_module) + dist_device.log_prob(action_device)
        dist_entropy = dist_module.entropy() + dist_device.entropy()
        state_values = self.critic(features)

        return action_logprobs, state_values, dist_entropy

class PPOAgent(BasePlacementAgent):
    def __init__(self):
        super(PPOAgent, self).__init__()

        # Environment constraints (10 Gateways + 1 Cloud, 150 IoT * 2 movable modules)
        self.max_candidates = 11
        self.max_modules = 300

        # State vector: [candidate_mips, candidate_loads, module_locations, module_mips]
        self.state_dim = (self.max_candidates * 2) + (self.max_modules * 2)

        self.policy = ActorCritic(self.state_dim, self.max_modules, self.max_candidates).to(DEVICE)
        self.optimizer = optim.Adam(self.policy.parameters(), lr=LR)
        self.policy_old = ActorCritic(self.state_dim, self.max_modules, self.max_candidates).to(DEVICE)
        self.policy_old.load_state_dict(self.policy.state_dict())

        self.memory = Memory()
        self.loss_fn = nn.MSELoss()

        self.cached_module_map = []
        self.cached_device_map = []

    def decide(self, state: dict) -> dict:
        """Fallback for static/sequential bridge scenarios."""
        candidates = self.candidate_devices(state)
        placement_map = {}
        for request in state.get("requests", []):
            per_req = {}
            for module in request["pendingModules"]:
                device = self._least_loaded(module, candidates)
                if device is not None:
                    per_req[module["name"]] = device["id"]
            placement_map[request["requestId"]] = per_req
        return self.build_action(placement_map)

    def decide_step(self, state: dict) -> dict:
        """Called every PLACEMENT_INTERVAL by the Java simulator."""
        step = state.get("step", 0)
        done = state.get("done", False)
        reward = state.get("reward", 0.0)

        devices = state.get("devices", [])
        candidates = sorted([d for d in devices if d["level"] < 2], key=lambda x: x["id"])

        placements = []
        migrations = []

        # 1. Handle Initial Placements (Step 0)
        if step == 0:
            committed_mips = {d["id"]: 0.0 for d in devices}
            for module in state.get("modules", []):
                if module["status"] == "pending":
                    device = self._least_loaded_raw(module, devices, committed_mips)
                    if device is not None:
                        placements.append({
                            "requestId": module["requestId"],
                            "module": module["name"],
                            "deviceId": device["id"]
                        })
                        committed_mips[device["id"]] += module["requiredMips"]
            return {"placements": placements, "migrations": migrations}

        # 2. Store previous reward (Shifted by 1 step)
        if step > 1:
            self.memory.rewards.append(reward)
            self.memory.is_terminals.append(False)

        # 3. Handle Episode End
        if done:
            self.memory.rewards.append(reward)
            self.memory.is_terminals.append(True)
            self._update_ppo()
            self.memory.clear()
            return {"placements": [], "migrations": []}

        # 4. Parse state for Neural Network (Strictly filter out data_preprocessor)
        active_modules = sorted([m for m in state.get("modules", [])
                                 if m["status"] == "placed" and m["name"] != "data_preprocessor"],
                                key=lambda x: (x["requestId"], x["name"]))

        self.cached_device_map = [d["id"] for d in candidates]
        self.cached_module_map = [{"req": m["requestId"], "name": m["name"]} for m in active_modules]

        state_tensor = self._build_state_tensor(candidates, active_modules)

        # 5. Get Action from PPO
        with torch.no_grad():
            mod_idx, dev_idx, logprob = self.policy_old.act(state_tensor)

        self.memory.states.append(state_tensor)
        self.memory.actions.append((mod_idx, dev_idx))
        self.memory.logprobs.append(logprob)

        # 6. Translate NN action to Simulator Migration command
        if mod_idx < len(self.cached_module_map) and dev_idx < len(self.cached_device_map):
            target_module = self.cached_module_map[mod_idx]
            target_device_id = self.cached_device_map[dev_idx]

            current_dev = -1
            req_mips = 0
            req_ram = 0
            for m in active_modules:
                if m["requestId"] == target_module["req"] and m["name"] == target_module["name"]:
                    current_dev = m["deviceId"]
                    req_mips = m["requiredMips"]
                    req_ram = m["requiredRam"]
                    break

            target_dev_info = next((d for d in candidates if d["id"] == target_device_id), None)

            # Verify capacity and ensure it's actually moving to a new device
            if target_dev_info and current_dev != target_device_id:
                free_mips = target_dev_info["availableMips"] - target_dev_info["currentLoad"]
                if free_mips >= req_mips and target_dev_info["availableRam"] >= req_ram:
                    migrations.append({
                        "requestId": target_module["req"],
                        "module": target_module["name"],
                        "toDeviceId": target_device_id
                    })

        return {"placements": [], "migrations": migrations}

    def _update_ppo(self):
        """Standard PPO backpropagation step."""
        if len(self.memory.states) == 0:
            return

        rewards = []
        discounted_reward = 0
        for reward, is_terminal in zip(reversed(self.memory.rewards), reversed(self.memory.is_terminals)):
            if is_terminal:
                discounted_reward = 0
            discounted_reward = reward + (GAMMA * discounted_reward)
            rewards.insert(0, discounted_reward)

        rewards = torch.tensor(rewards, dtype=torch.float32).to(DEVICE)
        rewards = (rewards - rewards.mean()) / (rewards.std() + 1e-7)

        old_states = torch.squeeze(torch.stack(self.memory.states, dim=0)).detach().to(DEVICE)
        old_actions_mod = torch.tensor([a[0] for a in self.memory.actions]).to(DEVICE)
        old_actions_dev = torch.tensor([a[1] for a in self.memory.actions]).to(DEVICE)
        old_logprobs = torch.squeeze(torch.stack(self.memory.logprobs, dim=0)).detach().to(DEVICE)

        for _ in range(K_EPOCHS):
            logprobs, state_values, dist_entropy = self.policy.evaluate(old_states, old_actions_mod, old_actions_dev)
            state_values = torch.squeeze(state_values)

            ratios = torch.exp(logprobs - old_logprobs.detach())
            advantages = rewards - state_values.detach()

            surr1 = ratios * advantages
            surr2 = torch.clamp(ratios, 1 - EPS_CLIP, 1 + EPS_CLIP) * advantages

            loss = -torch.min(surr1, surr2) + 0.5 * self.loss_fn(state_values, rewards) - 0.01 * dist_entropy

            self.optimizer.zero_grad()
            loss.mean().backward()
            self.optimizer.step()

        self.policy_old.load_state_dict(self.policy.state_dict())
        print("[PPO] Weights updated successfully.")

    def _build_state_tensor(self, candidates, active_modules):
        """Flattens dynamic lists into a fixed-size tensor."""
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

    def _least_loaded(self, module: dict, candidates: list[dict]) -> dict | None:
        sorted_candidates = sorted(candidates, key=lambda d: (-d["level"], -(self.available_mips(d))))
        for device in sorted_candidates:
            if (self.available_mips(device) >= module["requiredMips"] and device["availableRam"] >= module["requiredRam"]):
                return device
        return None

    @staticmethod
    def _least_loaded_raw(module: dict, devices: list[dict], committed_mips: dict[int, float]) -> dict | None:
        eligible = [d for d in devices if d["level"] < 2]
        sorted_devices = sorted(eligible, key=lambda d: (-d["level"], -(d["availableMips"] - committed_mips[d["id"]])))
        for device in sorted_devices:
            free_mips = device["availableMips"] - committed_mips[device["id"]]
            if free_mips >= module["requiredMips"] and device["availableRam"] >= module["requiredRam"]:
                return device
        return None