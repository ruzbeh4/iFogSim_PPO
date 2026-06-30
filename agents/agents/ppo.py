"""
ppo.py
──────
Placeholder agent for the PPO/DRL bridge (PPOBridgePlacementLogic.java).

This file intentionally does NOT implement a trained policy — no torch
model, no replay buffer, no training loop. It exists to prove the bridge
works end-to-end and to document exactly what a real PPO implementation
needs to plug in. Replace decide_step() with your trained policy.

Step protocol (see PPOBridgePlacementLogic.java for the Java side)
────────────────────────────────────────────────────────────────────────────
Called once per simulated PLACEMENT_INTERVAL, for the whole run (not just
once at t=0). Each call receives a `state` dict:

{
  "type": "step",
  "step": int,            # sequential tick counter
  "simTime": float,       # CloudSim clock (seconds)
  "done": bool,           # true on the last tick before the simulation ends
  "reward": float,        # reward for the PREVIOUS action this agent returned
                           #   reward = -(W_ENERGY * energyDeltaSinceLastStep
                           #              + W_LATENCY * currentLoopDelayMs)
                           #   i.e. higher (less negative) is better; the agent
                           #   is penalised for both energy growth and latency.
  "devices": [
    {"id":int, "name":str, "level":int, "parentId":int,
     "availableMips":float, "availableRam":float, "currentLoad":float}, ...
  ],
  "modules": [
    {"requestId":int, "name":str, "status":"placed"|"pending",
     "deviceId":int|None, "requiredMips":float, "requiredRam":float}, ...
  ]
}

and must return an action dict:

{
  "placements": [{"requestId":int, "module":str, "deviceId":int}, ...],
  "migrations":  [{"requestId":int, "module":str, "toDeviceId":int}, ...]
}

"placements" assigns modules with status=="pending" (only happens on the
very first tick or two in this scenario, since all 150 IoT clients submit
their requests at t=0). "migrations" moves a module that already has
status=="placed" to a different device — this is the lever a trained PPO
policy uses to react to changing load / device mobility over time.

Suggested observation/action space for a real implementation
────────────────────────────────────────────────────────────────────────────
Observation : per-device [availableMips, availableRam, currentLoad, level]
              concatenated with per-module [status, currentDeviceId, requiredMips].
Action      : MultiDiscrete over (module, targetDevice) pairs, or a single
              Discrete action per decision step if you decide one module's
              placement/migration per call instead of all at once.
Reward      : the `reward` field above — already computed Java-side from
              live energy + TimeKeeper latency, so no extra instrumentation
              is needed to start training.

@author M-H-Boroumandnia
"""

from .base_agent import BasePlacementAgent


class PPOAgent(BasePlacementAgent):
    """
    Bridge-verification placeholder. Until a trained policy is plugged in:
      - pending modules  → placed least-loaded (same heuristic as HeuristicAgent)
      - placed modules   → never migrated (safe no-op)
    """

    # ------------------------------------------------------------------
    # Static-bridge compatibility (decide() is abstract on BasePlacementAgent).
    # Only exercised if --agent ppo is accidentally pointed at the STATIC
    # bridge instead of PPOBridgePlacementLogic; the real entry point is
    # decide_step() below.
    # ------------------------------------------------------------------
    def decide(self, state: dict) -> dict:
        candidates = self.candidate_devices(state)
        placement_map: dict[int, dict[str, int]] = {}
        for request in state.get("requests", []):
            per_req: dict[str, int] = {}
            for module in request["pendingModules"]:
                device = self._least_loaded(module, candidates)
                if device is not None:
                    per_req[module["name"]] = device["id"]
            placement_map[request["requestId"]] = per_req
        return self.build_action(placement_map)

    # ------------------------------------------------------------------
    # Step-loop entry point — REPLACE THIS with your trained PPO policy.
    # ------------------------------------------------------------------
    def decide_step(self, state: dict) -> dict:
        """
        Called every PLACEMENT_INTERVAL for the whole simulation.

        TODO (real implementation):
          1. Build an observation vector from state["devices"] + state["modules"].
          2. Run the policy network forward pass to get an action.
          3. Step the PPO training update using state["reward"] from the
             PREVIOUS call (already computed by Java — see module docstring).
          4. On state["done"] == True, finalise the episode (e.g. bootstrap
             value, log to STEP_LOG-equivalent, reset any episode-local state).
        """
        devices = state.get("devices", [])
        placements: list[dict] = []
        migrations: list[dict] = []  # placeholder policy never migrates

        for module in state.get("modules", []):
            if module["status"] != "pending":
                continue
            device = self._least_loaded_raw(module, devices)
            if device is not None:
                placements.append({
                    "requestId": module["requestId"],
                    "module":    module["name"],
                    "deviceId":  device["id"],
                })

        return {"placements": placements, "migrations": migrations}

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _least_loaded(self, module: dict, candidates: list[dict]) -> dict | None:
        sorted_candidates = sorted(
            candidates,
            key=lambda d: (-d["level"], -(self.available_mips(d)))
        )
        for device in sorted_candidates:
            if (self.available_mips(device) >= module["requiredMips"]
                    and device["availableRam"] >= module["requiredRam"]):
                return device
        return None

    @staticmethod
    def _least_loaded_raw(module: dict, devices: list[dict]) -> dict | None:
        """Same idea as _least_loaded(), but for the step protocol's flat device list
        (no separate currentLoad accumulation needed — Java sends live availableMips)."""
        eligible = [d for d in devices if d["level"] < 2]  # exclude IoT/CLIENT leaves
        sorted_devices = sorted(eligible, key=lambda d: (-d["level"], -d["availableMips"]))
        for device in sorted_devices:
            if (device["availableMips"] >= module["requiredMips"]
                    and device["availableRam"] >= module["requiredRam"]):
                return device
        return None
