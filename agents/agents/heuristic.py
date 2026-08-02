"""Least-loaded heuristic with optional intentionally weak initial placement."""

from __future__ import annotations

from .base_agent import BasePlacementAgent


class HeuristicAgent(BasePlacementAgent):

    def __init__(self, bad_placement: bool = False):
        # When True, initial placement prefers cloud / avoids home fog so a
        # migration policy (Heuristic or PPO) has something to repair.
        self.bad_placement = bad_placement

    def decide(self, state: dict) -> dict:
        candidates = self.candidate_devices(state)
        devices = {device["id"]: device for device in state["devices"]}
        committed_mips: dict[int, float] = {device["id"]: 0.0 for device in candidates}
        committed_ram: dict[int, float] = {device["id"]: 0.0 for device in candidates}
        placement_map: dict[int, dict[str, int]] = {}

        for request in state["requests"]:
            req_id = request["requestId"]
            home_fog_id = self._home_fog_id(request, devices, candidates)
            per_req: dict[str, int] = {}

            for module in request["pendingModules"]:
                best_device_id = self._pick_device(
                    module, candidates, committed_mips, committed_ram, home_fog_id,
                )
                if best_device_id is not None:
                    per_req[module["name"]] = best_device_id
                    committed_mips[best_device_id] += module["requiredMips"]
                    committed_ram[best_device_id] += module["requiredRam"]
                else:
                    fallback_id = candidates[0]["id"]
                    per_req[module["name"]] = fallback_id
                    print(
                        f"[Heuristic] WARNING: no device with enough capacity for "
                        f"'{module['name']}'; falling back to {candidates[0]['name']}"
                    )
            placement_map[req_id] = per_req

        return self.build_action(placement_map)

    def decide_step(self, state: dict, max_migrations: int = 4) -> dict:
        """Online migrations using home-fog / least-loaded preference (always good)."""
        actors = state.get("actors", [])
        if bool(state.get("done", False)) or not actors:
            return {"actions": []}

        free_mips = {
            int(device["id"]): float(device["freeMips"])
            for device in state.get("devices", [])
        }
        actions: list[dict] = []
        migrations = 0

        for actor in actors:
            current_id = int(actor["currentDeviceId"])
            required = float(actor["requiredMips"])
            required_ram = float(actor["requiredRam"])
            destination = current_id

            if migrations < max_migrations:
                destination = self._pick_migration_target(
                    actor.get("candidates", []),
                    current_id,
                    required,
                    required_ram,
                    free_mips,
                )

            if destination != current_id:
                migrations += 1
                free_mips[destination] = free_mips.get(destination, 0.0) - required
                free_mips[current_id] = free_mips.get(current_id, 0.0) + required

            actions.append({
                "actorId": str(actor["actorId"]),
                "toDeviceId": int(destination),
            })

        return {"actions": actions}

    @staticmethod
    def _home_fog_id(request: dict, devices: dict, candidates: list[dict]) -> int | None:
        gateway = devices.get(int(request.get("gatewayDeviceId", -1)))
        if gateway is None:
            return None
        parent_id = int(gateway.get("parentId", -1))
        if any(device["id"] == parent_id for device in candidates):
            return parent_id
        return None

    def _pick_device(
        self,
        module: dict,
        candidates: list[dict],
        committed_mips: dict[int, float],
        committed_ram: dict[int, float],
        home_fog_id: int | None,
    ) -> int | None:
        required_mips = module["requiredMips"]
        required_ram = module["requiredRam"]

        def sort_key(device: dict):
            free_mips = self.available_mips(device) - committed_mips[device["id"]]
            if self.bad_placement:
                # Prefer cloud, avoid home fog, pack onto already-busy devices.
                prefer_cloud = 0 if device["level"] == 0 else 1
                avoid_home = (
                    1 if home_fog_id is not None and device["id"] == home_fog_id else 0
                )
                return (prefer_cloud, avoid_home, free_mips)
            is_home = 0 if home_fog_id is not None and device["id"] == home_fog_id else 1
            is_cloud = 0 if device["level"] > 0 else 1
            return (is_home, is_cloud, -device["level"], -free_mips)

        for device in sorted(candidates, key=sort_key):
            free_mips = self.available_mips(device) - committed_mips[device["id"]]
            free_ram = device["availableRam"] - committed_ram[device["id"]]
            if free_mips >= required_mips and free_ram >= required_ram:
                return device["id"]
        return None

    @staticmethod
    def _pick_migration_target(
        candidates: list[dict],
        current_id: int,
        required_mips: float,
        required_ram: float,
        free_mips: dict[int, float],
    ) -> int:
        """Prefer home gateway, then non-cloud / edge, then most free MIPS."""

        def sort_key(option: dict):
            device_id = int(option["deviceId"])
            available = free_mips.get(device_id, float(option.get("freeMips", 0.0)))
            is_home = 0 if option.get("isHomeGateway") else 1
            is_cloud = 0 if int(option.get("level", 0)) > 0 else 1
            # Prefer staying put when rank is otherwise equal.
            is_current = 0 if device_id == current_id else 1
            return (is_home, is_cloud, -int(option.get("level", 0)), is_current, -available)

        for option in sorted(candidates, key=sort_key):
            device_id = int(option["deviceId"])
            if device_id == current_id:
                return current_id
            available = free_mips.get(device_id, float(option.get("freeMips", 0.0)))
            free_ram = float(option.get("freeRam", 0.0))
            feasible = bool(option.get("feasible", True))
            if feasible and available + 1.0e-9 >= required_mips and free_ram + 1.0e-9 >= required_ram:
                return device_id
        return current_id
