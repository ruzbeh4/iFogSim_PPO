"""Plot shared-PPO metrics from per-episode JSON files.

Examples:
    python agents/plot_shared_episode_results.py
    python agents/plot_shared_episode_results.py --show --rolling-window 10
"""

from __future__ import annotations

import argparse
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
        recent = [item for item in values[max(0, index - window + 1):index + 1] if item is not None]
        result.append(sum(recent) / len(recent) if recent else None)
    return result


def series(history: list[dict], key: str) -> list[float | None]:
    values: list[float | None] = []
    for item in history:
        value = item.get(key)
        values.append(None if value is None else float(value))
    return values


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


def discover_history(results_dir: Path) -> list[dict]:
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


def plot(history: list[dict], output: Path, show: bool, window: int) -> None:
    if not show:
        import matplotlib
        matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    episodes = [int(item.get("episode", index + 1)) for index, item in enumerate(history)]
    figure, axes = plt.subplots(2, 3, figsize=(16, 9), constrained_layout=True)
    figure.suptitle("Shared PPO episode results")

    reward = series(history, "meanLocalReward")
    axes[0, 0].plot(episodes, reward, alpha=0.35, label="mean local reward")
    if window > 1:
        axes[0, 0].plot(episodes, rolling(reward, window), linewidth=2, label=f"{window}-episode mean")
    axes[0, 0].set(title="Reward", xlabel="episode seed", ylabel="reward")
    axes[0, 0].legend()

    latency = series(history, "averageLatency")
    if all(value is None for value in latency):
        latency = series(history, "loopDelay")
    axes[0, 1].plot(episodes, latency, label="average latency")
    critical_latency = series(history, "criticalLoopDelay")
    if any(value is not None for value in critical_latency):
        axes[0, 1].plot(episodes, critical_latency, label="critical latency")
    axes[0, 1].set(title="Latency", xlabel="episode seed", ylabel="ms")
    axes[0, 1].legend()

    energy = series(history, "totalEnergy")
    cloud_energy = series(history, "cloudEnergy")
    axes[0, 2].plot(episodes, energy, color="tab:orange", alpha=0.35, label="total energy")
    if any(value is not None for value in cloud_energy):
        axes[0, 2].plot(episodes, cloud_energy, color="tab:red", label="cloud energy")
    if window > 1:
        axes[0, 2].plot(episodes, rolling(energy, window), color="tab:brown", linewidth=2,
                        label=f"{window}-episode total mean")
    axes[0, 2].set(title="Energy", xlabel="episode seed", ylabel="J")
    axes[0, 2].ticklabel_format(axis="y", style="sci", scilimits=(0, 0))
    axes[0, 2].legend()

    success = series(history, "criticalDeadlineSuccessRate")
    axes[1, 0].plot(
        episodes,
        [100 * value if value is not None else None for value in success],
        color="tab:green",
    )
    axes[1, 0].set(title="Critical on-time rate", xlabel="episode seed", ylabel="%")

    accepted = series(history, "acceptedMigrations")
    rejected = series(history, "rejectedMigrations")
    axes[1, 1].plot(episodes, accepted, color="tab:purple", label="accepted")
    if any(value is not None for value in rejected):
        axes[1, 1].plot(episodes, rejected, color="tab:pink", label="rejected")
    axes[1, 1].set(title="Migrations", xlabel="episode seed", ylabel="count")
    axes[1, 1].legend()

    cloud_cost = series(history, "cloudCost")
    axes[1, 2].plot(episodes, cloud_cost, color="tab:blue", label="cloud cost")
    if window > 1:
        axes[1, 2].plot(episodes, rolling(cloud_cost, window), color="tab:cyan", linewidth=2,
                        label=f"{window}-episode mean")
    axes[1, 2].set(title="Cloud cost", xlabel="episode seed", ylabel="cost")
    axes[1, 2].legend()

    output.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(output, dpi=160)
    print(f"Wrote {output}")
    print(f"Plotted {len(history)} episode files")
    if show:
        plt.show()


def main() -> None:
    root = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="Plot shared-PPO per-episode results")
    parser.add_argument("--results-dir", type=Path, default=root / "results" / "shared_ppo")
    parser.add_argument("--output", type=Path, default=root / "results" / "shared_ppo_episode_results.png")
    parser.add_argument("--rolling-window", type=int, default=5)
    parser.add_argument("--show", action="store_true")
    args = parser.parse_args()

    history = discover_history(args.results_dir)
    plot(history, args.output, args.show, max(1, args.rolling_window))


if __name__ == "__main__":
    main()
