"""Abstract base class for microservice placement agents."""

from abc import ABC, abstractmethod


class BasePlacementAgent(ABC):

    @abstractmethod
    def decide(self, state: dict) -> dict:
        """Return {"placement": {requestId: {moduleName: deviceId}}}."""

    @staticmethod
    def candidate_devices(state: dict) -> list[dict]:
        """Devices eligible to host modules (level < 2)."""
        return [d for d in state["devices"] if d["level"] < 2]

    @staticmethod
    def available_mips(device: dict) -> float:
        return device["availableMips"] - device["currentLoad"]

    @staticmethod
    def build_action(placement_map: dict) -> dict:
        return {"placement": {str(k): v for k, v in placement_map.items()}}
