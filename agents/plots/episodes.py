"""Plot shared-PPO metrics from per-episode JSON files."""

from __future__ import annotations

import argparse
from pathlib import Path

from utils.plotting import configure_matplotlib, load_episode_history, rolling, series
from utils.results_paths import latest_single_dir


def plot(history: list[dict], output: Path, show: bool, window: int) -> None:
    plt = configure_matplotlib(show)
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
        axes[0, 2].plot(
            episodes, rolling(energy, window), color="tab:brown", linewidth=2,
            label=f"{window}-episode total mean",
        )
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
        axes[1, 2].plot(
            episodes, rolling(cloud_cost, window), color="tab:cyan", linewidth=2,
            label=f"{window}-episode mean",
        )
    axes[1, 2].set(title="Cloud cost", xlabel="episode seed", ylabel="cost")
    axes[1, 2].legend()

    output.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(output, dpi=160)
    print(f"Wrote {output}")
    print(f"Plotted {len(history)} episode files")
    if show:
        plt.show()
    plt.close(figure)


def main() -> None:
    parser = argparse.ArgumentParser(description="Plot shared-PPO per-episode results")
    parser.add_argument("--results-dir", type=Path, default=None)
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--rolling-window", type=int, default=5)
    parser.add_argument("--show", action="store_true")
    args = parser.parse_args()

    results_dir = args.results_dir or latest_single_dir("shared_ppo")
    if results_dir is None:
        raise SystemExit("No single run found under agents/results/<date>/single/")
    results_dir = Path(results_dir)
    output = args.output or (results_dir / "episode_results.png")
    plot(load_episode_history(results_dir), output, args.show, max(1, args.rolling_window))


if __name__ == "__main__":
    main()
