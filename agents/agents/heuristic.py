"""
heuristic.py
────────────
Least-Loaded (Shortest-Queue) heuristic placement agent.

This is the *baseline* strategy used for comparison against the Genetic
Algorithm and the PPO agent.  It makes a greedy, per-module decision:

  For each pending module, pick the eligible fog/cloud device that has
  the most remaining MIPS capacity (i.e. the "shortest queue").  If the
  chosen device does not have enough RAM, fall back to the next best.

Design notes
────────────
• No global optimisation is attempted — decisions are independent per module.
• Level-0 devices (cloud) are considered last by sorting on a secondary key
  (level ascending, then free MIPS descending) so that edge devices are
  preferred over the cloud when capacity allows.  This mirrors the
  "edge-first" philosophy of fog computing.
• The agent is stateless — it does not retain information between calls,
  which makes it straightforward to swap in/out during comparison runs.

@author M-H-Boroumandnia
"""

from .base_agent import BasePlacementAgent


class HeuristicAgent(BasePlacementAgent):
    """Greedy Least-Loaded placement — edge devices preferred over cloud."""

    def decide(self, state: dict) -> dict:
        """
        For each placement request, assign every pending module to the
        device with the most free MIPS, preferring lower-level (edge) nodes.

        Parameters
        ----------
        state : dict
            Simulation state from the Java bridge.

        Returns
        -------
        dict
            Action: {"placement": {"<requestId>": {"<module>": <deviceId>}}}
        """
        candidates = self.candidate_devices(state)

        # Track MIPS consumed *within this call* so that multiple modules from
        # different requests do not all race to the same lightly-loaded device.
        # Keys are device IDs.
        committed_mips: dict[int, float] = {d["id"]: 0.0 for d in candidates}
        committed_ram:  dict[int, float] = {d["id"]: 0.0 for d in candidates}

        placement_map: dict[int, dict[str, int]] = {}

        for request in state["requests"]:
            req_id   = request["requestId"]
            per_req: dict[str, int] = {}

            for module in request["pendingModules"]:
                best_device_id = self._pick_device(
                    module, candidates, committed_mips, committed_ram)

                if best_device_id is not None:
                    per_req[module["name"]] = best_device_id
                    committed_mips[best_device_id] += module["requiredMips"]
                    committed_ram[best_device_id]  += module["requiredRam"]
                else:
                    # Fallback: cloud always has enough capacity in practice;
                    # grab the first candidate as a last resort.
                    fallback_id = candidates[0]["id"]
                    per_req[module["name"]] = fallback_id
                    print(f"[Heuristic] WARNING: no device with enough capacity for "
                          f"'{module['name']}'; falling back to {candidates[0]['name']}")

            placement_map[req_id] = per_req

        return self.build_action(placement_map)

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _pick_device(
        self,
        module: dict,
        candidates: list[dict],
        committed_mips: dict[int, float],
        committed_ram:  dict[int, float],
    ) -> int | None:
        """
        Selects the best device for a single module.

        Sorting criteria (primary → secondary):
          1. level ascending  – prefer edge over cloud
          2. free MIPS desc   – prefer least-loaded among same-level nodes

        Returns None if no candidate has sufficient MIPS AND RAM.
        """
        required_mips = module["requiredMips"]
        required_ram  = module["requiredRam"]

        # Sort: edge first (level DESC — fog=1 before cloud=0),
        # then most free MIPS first within the same level.
        sorted_candidates = sorted(
            candidates,
            key=lambda d: (
                -d["level"],
                -(self.available_mips(d) - committed_mips[d["id"]])
            )
        )

        for device in sorted_candidates:
            dev_id    = device["id"]
            free_mips = self.available_mips(device) - committed_mips[dev_id]
            free_ram  = device["availableRam"]       - committed_ram[dev_id]

            if free_mips >= required_mips and free_ram >= required_ram:
                return dev_id

        return None  # caller handles the fallback
