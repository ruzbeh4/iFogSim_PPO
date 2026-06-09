"""
base_agent.py
─────────────
Abstract base class for all placement agents.

Every agent receives a *state* dict (deserialised from the Java bridge JSON)
and must return an *action* dict in the format the Java bridge expects.

State schema
────────────
{
  "step": int,
  "devices": [
    {
      "id": int,
      "name": str,
      "level": int,          # 0 = cloud, 1 = fog gateway, 2 = IoT client
      "parentId": int,
      "availableMips": float,
      "availableRam":  float,
      "currentLoad":   float  # MIPS already committed on this device
    }, ...
  ],
  "requests": [
    {
      "requestId":      int,
      "appId":          str,
      "gatewayDeviceId":int,
      "alreadyPlaced":  { moduleName: deviceId, ... },
      "pendingModules": [
        { "name": str, "requiredMips": float, "requiredRam": float }, ...
      ]
    }, ...
  ],
  "allModules": [str, ...]
}

Action schema (returned by decide())
─────────────────────────────────────
{
  "placement": {
    "<requestId>": { "<moduleName>": <deviceId>, ... },
    ...
  }
}

@author M-H-Boroumandnia
"""

from abc import ABC, abstractmethod


class BasePlacementAgent(ABC):
    """Abstract base class for microservice placement agents."""

    # ------------------------------------------------------------------
    # Interface
    # ------------------------------------------------------------------

    @abstractmethod
    def decide(self, state: dict) -> dict:
        """
        Compute a placement decision for the given simulation state.

        Parameters
        ----------
        state : dict
            Deserialised JSON state message from the Java simulator.

        Returns
        -------
        dict
            Action message: {"placement": {requestId: {moduleName: deviceId}}}
        """

    # ------------------------------------------------------------------
    # Shared helpers available to all subclasses
    # ------------------------------------------------------------------

    @staticmethod
    def candidate_devices(state: dict) -> list[dict]:
        """
        Returns all devices that are eligible to host modules —
        i.e. everything *except* CLIENT-level (level 2) IoT devices.
        CLIENT devices are leaf nodes running constrained hardware; the
        data_preprocessor is pre-placed there before this agent is called.
        """
        return [d for d in state["devices"] if d["level"] < 2]

    @staticmethod
    def available_mips(device: dict) -> float:
        """Remaining MIPS capacity on a device after its current load."""
        return device["availableMips"] - device["currentLoad"]

    @staticmethod
    def build_action(placement_map: dict) -> dict:
        """
        Wraps a {requestId: {moduleName: deviceId}} dict in the expected
        action envelope.
        """
        # JSON keys must be strings; Java's parser reads them as strings too
        return {"placement": {str(k): v for k, v in placement_map.items()}}
