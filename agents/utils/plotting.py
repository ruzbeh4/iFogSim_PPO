"""Shared helpers for result plotting."""

from __future__ import annotations

import json
import re
from pathlib import Path

EPISODE_FILE_RE = re.compile(r"episode_(?P<seed>\d+)_(?P<stamp>\d{8}_\d{6})\.json$")


def rolling(values: list[float | None], window: int) -> list[float | None]:
    result: list[float | None] = []
    for index, value in enumerate(values):
        if value is None:
            result.append(None)
            continue
        recent = [
            item for item in values[max(0, index - window + 1):index + 1]
            if item is not None
        ]
        result.append(sum(recent) / len(recent) if recent else None)
    return result


def series(history: list[dict], key: str) -> list[float | None]:
    values: list[float | None] = []
    for item in history:
        value = item.get(key)
        values.append(None if value is None else float(value))
    return values


def metric(payload: dict, key: str) -> float | None:
    value = payload.get(key)
    return None if value is None else float(value)


def mean(values: list[float]) -> float | None:
    return sum(values) / len(values) if values else None


def device_energy_split(payload: dict) -> tuple[float | None, float | None]:
    devices = payload.get("energyPerDevice")
    if not isinstance(devices, list) or not devices:
        return None, None
    cloud = 0.0
    edge = 0.0
    for device in devices:
        try:
            energy = float(device.get("energy", 0.0))
            level = int(device.get("level", -1))
        except (TypeError, ValueError):
            continue
        if level == 0:
            cloud += energy
        else:
            edge += energy
    return cloud, edge


def load_episode_history(results_dir: Path) -> list[dict]:
    if not results_dir.is_dir():
        raise SystemExit(f"Results directory not found: {results_dir}")

    latest_by_seed: dict[int, tuple[str, Path]] = {}
    for path in results_dir.iterdir():
        if not path.is_file() or path.suffix.lower() != ".json":
            continue
        match = EPISODE_FILE_RE.fullmatch(path.name)
        if not match:
            continue
        seed = int(match.group("seed"))
        stamp = match.group("stamp")
        current = latest_by_seed.get(seed)
        if current is None or stamp > current[0]:
            latest_by_seed[seed] = (stamp, path)

    if not latest_by_seed:
        raise SystemExit(f"No episode_*.json files found in {results_dir}")

    history: list[dict] = []
    for seed in sorted(latest_by_seed):
        _, path = latest_by_seed[seed]
        with path.open(encoding="utf-8") as handle:
            payload = json.load(handle)
        if not isinstance(payload, dict):
            continue
        payload["episode"] = seed
        payload["_source_file"] = path.name
        cloud_energy, edge_energy = device_energy_split(payload)
        payload["cloudEnergy"] = cloud_energy
        payload["edgeEnergy"] = edge_energy
        history.append(payload)
    return history


def configure_matplotlib(show: bool):
    import matplotlib
    if not show:
        matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    return plt
