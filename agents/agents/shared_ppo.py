"""Shared service-level PPO agent for the revised iFogSim training bridge."""

from __future__ import annotations

from dataclasses import dataclass
import json
import os
import random
from typing import Any

import numpy as np
import torch
from torch import nn
from torch.distributions import Categorical

from .genetic import GeneticAgent
from .heuristic import HeuristicAgent


DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
FEATURE_DIM = 20
GAMMA = 0.98
CLIP_EPS = 0.2
ENTROPY_COEF = 0.01
VALUE_COEF = 0.5
LEARNING_RATE = 3.0e-4
PPO_EPOCHS = 6
MINIBATCH_SIZE = 256
PLACEMENT_INIT_CHOICES = ("genetic", "heuristic", "bad_heuristic")


class SharedActorCritic(nn.Module):

    def __init__(self, feature_dim: int = FEATURE_DIM):
        super().__init__()
        self.encoder = nn.Sequential(
            nn.Linear(feature_dim, 128), nn.LayerNorm(128), nn.Tanh(),
            nn.Linear(128, 128), nn.Tanh(),
        )
        self.actor = nn.Linear(128, 1)
        self.critic = nn.Sequential(nn.Linear(128, 64), nn.Tanh(), nn.Linear(64, 1))

    def evaluate_candidates(
        self, features: torch.Tensor, mask: torch.Tensor
    ) -> tuple[Categorical, torch.Tensor]:
        encoded = self.encoder(features)
        logits = self.actor(encoded).squeeze(-1).masked_fill(~mask, -1.0e9)
        distribution = Categorical(logits=logits)
        value = self.critic(encoded.mean(dim=0)).squeeze(-1)
        return distribution, value


@dataclass
class PendingDecision:
    actor_id: str
    features: torch.Tensor
    mask: torch.Tensor
    action: int
    logprob: float
    value: float


@dataclass
class Transition:
    features: torch.Tensor
    mask: torch.Tensor
    action: int
    old_logprob: float
    target: float
    advantage: float
    reward: float


class SharedPPOAgent:

    def __init__(
        self,
        model_path: str | None = None,
        convergence_path: str | None = None,
        training: bool = True,
        max_migrations_per_step: int = 2,
        verbose: bool = False,
        placement_init: str = "genetic",
    ):
        base_dir = os.path.dirname(os.path.dirname(__file__))
        self.model_path = model_path or os.path.join(base_dir, "results", "model.pth")
        self.convergence_path = convergence_path or os.path.join(
            os.path.dirname(self.model_path), "convergence.json")
        self.training = training
        self.max_migrations_per_step = max(0, max_migrations_per_step)
        self.verbose = verbose
        if placement_init not in PLACEMENT_INIT_CHOICES:
            raise ValueError(
                f"placement_init must be one of {PLACEMENT_INIT_CHOICES}, got {placement_init!r}"
            )
        self.placement_init = placement_init
        self.policy = SharedActorCritic().to(DEVICE)
        self.optimizer = torch.optim.Adam(self.policy.parameters(), lr=LEARNING_RATE)
        self.genetic = GeneticAgent()
        self.heuristic = HeuristicAgent(bad_placement=False)
        self.bad_heuristic = HeuristicAgent(bad_placement=True)
        self.pending: dict[str, PendingDecision] = {}
        self.transitions: list[Transition] = []
        self.episode = 0
        self.convergence: list[dict[str, Any]] = []
        self._load_if_present()

    def _load_if_present(self) -> None:
        if not os.path.exists(self.model_path):
            return
        checkpoint = torch.load(self.model_path, map_location=DEVICE, weights_only=False)
        if isinstance(checkpoint, dict) and "policy" in checkpoint:
            self.policy.load_state_dict(checkpoint["policy"])
            if self.training and "optimizer" in checkpoint:
                self.optimizer.load_state_dict(checkpoint["optimizer"])
            self.episode = int(checkpoint.get("episode", 0))
            self.convergence = list(checkpoint.get("convergence", []))
        else:
            self.policy.load_state_dict(checkpoint)
        if self.verbose:
            print(f"[SharedPPO] Loaded checkpoint: {self.model_path}")

    def decide_initial(self, state: dict[str, Any]) -> dict[str, Any]:
        seed = int(state.get("episodeSeed", 1))
        random.seed(seed)
        np.random.seed(seed & 0xFFFFFFFF)
        torch.manual_seed(seed)
        ga_state = {key: state[key] for key in ("step", "devices", "requests", "allModules")}
        if self.placement_init == "bad_heuristic":
            decision = self.bad_heuristic.decide(ga_state)
        elif self.placement_init == "heuristic":
            decision = self.heuristic.decide(ga_state)
        else:
            decision = self.genetic.decide(ga_state)
        placements = []
        for request_id, modules in decision.get("placement", {}).items():
            for module, device_id in modules.items():
                placements.append({
                    "requestId": int(request_id),
                    "module": module,
                    "deviceId": int(device_id),
                })
        return {"placements": placements, "migrations": []}

    def decide_step(self, state: dict[str, Any]) -> dict[str, Any]:
        actors = state.get("actors", [])
        done = bool(state.get("done", False))
        self._finish_pending(actors, done)
        if done:
            self.pending.clear()
            return {"actions": []}

        actions: list[dict[str, Any]] = []
        reservations = self._initial_free_cpu(state)
        migrations = 0

        for actor in actors:
            features, mask, device_ids = self._tensorize(actor, reservations)
            current_index = device_ids.index(int(actor["currentDeviceId"]))
            if migrations >= self.max_migrations_per_step:
                mask[:] = False
                mask[current_index] = True

            with torch.no_grad():
                distribution, value = self.policy.evaluate_candidates(features, mask)
                action_tensor = distribution.sample() if self.training else torch.argmax(distribution.logits)
                logprob = distribution.log_prob(action_tensor)

            action_index = int(action_tensor.item())
            destination = device_ids[action_index]
            source = int(actor["currentDeviceId"])
            required = float(actor["requiredMips"])
            if destination != source:
                migrations += 1
                reservations[destination] -= required
                reservations[source] = reservations.get(source, 0.0) + required

            actor_id = str(actor["actorId"])
            self.pending[actor_id] = PendingDecision(
                actor_id=actor_id,
                features=features.detach().cpu(),
                mask=mask.detach().cpu(),
                action=action_index,
                logprob=float(logprob.item()),
                value=float(value.item()),
            )
            actions.append({"actorId": actor_id, "toDeviceId": destination})

        return {"actions": actions}

    def _finish_pending(self, actors: list[dict[str, Any]], done: bool) -> None:
        for actor in actors:
            actor_id = str(actor["actorId"])
            pending = self.pending.pop(actor_id, None)
            if pending is None:
                continue
            reward = float(actor.get("reward", {}).get("total", 0.0))
            next_value = 0.0
            if not done:
                features, mask, _ = self._tensorize(actor, None)
                with torch.no_grad():
                    _, value = self.policy.evaluate_candidates(features, mask)
                    next_value = float(value.item())
            target = reward + (0.0 if done else GAMMA * next_value)
            self.transitions.append(Transition(
                features=pending.features,
                mask=pending.mask,
                action=pending.action,
                old_logprob=pending.logprob,
                target=target,
                advantage=target - pending.value,
                reward=reward,
            ))

    @staticmethod
    def _initial_free_cpu(state: dict[str, Any]) -> dict[int, float]:
        return {
            int(device["id"]): float(device["freeMips"])
            for device in state.get("devices", [])
        }

    def _tensorize(
        self, actor: dict[str, Any], reservations: dict[int, float] | None
    ) -> tuple[torch.Tensor, torch.Tensor, list[int]]:
        rows: list[list[float]] = []
        masks: list[bool] = []
        device_ids: list[int] = []
        current = int(actor["currentDeviceId"])
        required = max(float(actor["requiredMips"]), 1.0)
        module = str(actor.get("module", ""))
        module_sa = 1.0 if module == "smart_analyzer" else 0.0
        module_ac = 1.0 if module == "actuator_controller" else 0.0
        current_latency = float(actor.get("currentLatency", 0.0))

        for candidate in actor.get("candidates", []):
            device_id = int(candidate["deviceId"])
            free_mips = float(candidate["freeMips"])
            if reservations is not None:
                free_mips = reservations.get(device_id, free_mips)
            capacity = max(float(candidate["cpuCapacity"]), 1.0)
            ram_capacity = max(float(candidate["ramCapacity"]), 1.0)
            estimated_latency = float(candidate["estimatedLatency"])
            feasible = bool(candidate.get("feasible", False))
            if device_id != current:
                feasible = feasible and free_mips + 1.0e-9 >= required

            rows.append([
                module_sa,
                module_ac,
                min(required / 5000.0, 2.0),
                min(float(actor["requiredRam"]) / 4096.0, 2.0),
                float(actor.get("mobileClient", False)),
                float(actor.get("currentLevel", 0)) / 2.0,
                float(candidate.get("level", 0)) / 2.0,
                float(candidate.get("isCurrent", False)),
                float(candidate.get("isHomeGateway", False)),
                float(candidate.get("sameAsPeer", False)),
                np.clip(free_mips / capacity, -1.0, 1.0),
                np.clip(float(candidate["freeRam"]) / ram_capacity, 0.0, 1.0),
                np.clip(float(candidate.get("utilization", 0.0)), 0.0, 1.0),
                np.clip(float(candidate.get("currentUtilization", 0.0)), 0.0, 1.0),
                min(estimated_latency / 500.0, 2.0),
                np.clip((estimated_latency - current_latency) / 500.0, -2.0, 2.0),
                min(float(candidate.get("energyDelta", 0.0)) / 2000.0, 2.0),
                min(float(candidate.get("currentEnergyDelta", 0.0)) / 2000.0, 2.0),
                min(float(candidate.get("pathToClient", 0.0)) / 250.0, 2.0),
                float(feasible),
            ])
            masks.append(feasible or device_id == current)
            device_ids.append(device_id)

        if not rows:
            raise ValueError(f"Actor {actor.get('actorId')} has no candidates")
        return (
            torch.tensor(rows, dtype=torch.float32, device=DEVICE),
            torch.tensor(masks, dtype=torch.bool, device=DEVICE),
            device_ids,
        )

    def on_episode_end(self, results: dict[str, Any]) -> None:
        update_stats = self._update_policy() if self.training else {"loss": 0.0}
        self.episode += 1
        summary = {
            "episode": self.episode,
            "episodeSeed": results.get("episodeSeed"),
            "totalEnergy": results.get("totalEnergy", 0.0),
            "loopDelay": results.get("loopDelay"),
            "averageLatency": results.get("averageLatency", results.get("loopDelay")),
            "normalLoopDelay": results.get("normalLoopDelay"),
            "criticalLoopDelay": results.get("criticalLoopDelay"),
            "meanLocalReward": results.get("meanLocalReward"),
            "criticalTasks": results.get("criticalTasks", 0),
            "criticalTasksOnTime": results.get("criticalTasksOnTime", 0),
            "criticalTasksMissed": results.get("criticalTasksMissed", 0),
            "criticalTasksPending": results.get("criticalTasksPending", 0),
            "criticalTasksEvaluated": results.get("criticalTasksEvaluated", 0),
            "criticalDeadlineSuccessRate": results.get("criticalDeadlineSuccessRate"),
            "criticalDeadlineMeanMs": results.get("criticalDeadlineMeanMs"),
            "migrations": results.get("migrationCount", 0),
            "acceptedMigrations": results.get("acceptedMigrations", 0),
            "rejectedMigrations": results.get("rejectedMigrations", 0),
            **update_stats,
        }
        self.convergence.append(summary)
        if self.training:
            self._save()
        else:
            os.makedirs(os.path.dirname(self.convergence_path) or ".", exist_ok=True)
            with open(self.convergence_path, "w", encoding="utf-8") as handle:
                json.dump(self.convergence, handle, indent=2)
        self.transitions.clear()
        self.pending.clear()
        if self.verbose:
            print("[SharedPPO] " + json.dumps(summary, sort_keys=True))

    def _update_policy(self) -> dict[str, float]:
        if not self.transitions:
            return {"samples": 0, "loss": 0.0, "meanTdTarget": 0.0,
                    "meanTransitionReward": 0.0}
        advantages = torch.tensor([t.advantage for t in self.transitions], dtype=torch.float32, device=DEVICE)
        advantages = (advantages - advantages.mean()) / (advantages.std(unbiased=False) + 1.0e-8)
        indices = np.arange(len(self.transitions))
        losses: list[float] = []

        self.policy.train()
        for _ in range(PPO_EPOCHS):
            np.random.shuffle(indices)
            for start in range(0, len(indices), MINIBATCH_SIZE):
                batch = indices[start:start + MINIBATCH_SIZE]
                terms = []
                for idx in batch:
                    transition = self.transitions[int(idx)]
                    features = transition.features.to(DEVICE)
                    mask = transition.mask.to(DEVICE)
                    distribution, value = self.policy.evaluate_candidates(features, mask)
                    action = torch.tensor(transition.action, device=DEVICE)
                    new_logprob = distribution.log_prob(action)
                    ratio = torch.exp(new_logprob - transition.old_logprob)
                    advantage = advantages[int(idx)]
                    surrogate = torch.minimum(
                        ratio * advantage,
                        torch.clamp(ratio, 1.0 - CLIP_EPS, 1.0 + CLIP_EPS) * advantage,
                    )
                    target = torch.tensor(transition.target, dtype=torch.float32, device=DEVICE)
                    terms.append(
                        -surrogate
                        + VALUE_COEF * torch.square(value - target)
                        - ENTROPY_COEF * distribution.entropy()
                    )
                loss = torch.stack(terms).mean()
                self.optimizer.zero_grad()
                loss.backward()
                nn.utils.clip_grad_norm_(self.policy.parameters(), 0.5)
                self.optimizer.step()
                losses.append(float(loss.item()))
        return {
            "samples": len(self.transitions),
            "loss": float(np.mean(losses)),
            "meanTdTarget": float(np.mean([t.target for t in self.transitions])),
            "meanTransitionReward": float(np.mean([t.reward for t in self.transitions])),
        }

    def _save(self) -> None:
        os.makedirs(os.path.dirname(self.model_path), exist_ok=True)
        torch.save({
            "policy": self.policy.state_dict(),
            "optimizer": self.optimizer.state_dict(),
            "episode": self.episode,
            "convergence": self.convergence,
            "feature_dim": FEATURE_DIM,
        }, self.model_path)
        os.makedirs(os.path.dirname(self.convergence_path), exist_ok=True)
        with open(self.convergence_path, "w", encoding="utf-8") as handle:
            json.dump(self.convergence, handle, indent=2)
