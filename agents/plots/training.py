"""Plot shared-PPO training metrics from convergence JSON."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from utils.plotting import configure_matplotlib, rolling, series
from utils.results_paths import latest_single_dir


def plot(history: list[dict], output: Path, show: bool, window: int) -> None:
    plt = configure_matplotlib(show)
    episodes = [int(item.get("episode", index + 1)) for index, item in enumerate(history)]
    figure, axes = plt.subplots(2, 2, figsize=(13, 8), constrained_layout=True)
    figure.suptitle("Shared service-level PPO training")

    reward = series(history, "meanLocalReward")
    reward_label = "mean local reward"
    if all(value is None for value in reward):
        reward = series(history, "meanRewardTarget")
        reward_label = "TD target (legacy)"
    axes[0, 0].plot(episodes, reward, alpha=0.35, label=reward_label)
    if window > 1:
        axes[0, 0].plot(
            episodes, rolling(reward, window), linewidth=2, label=f"{window}-episode mean",
        )
    axes[0, 0].set(title="Reward", xlabel="episode", ylabel="reward")
    axes[0, 0].legend()

    latency = series(history, "averageLatency")
    if all(value is None for value in latency):
        latency = series(history, "loopDelay")
    axes[0, 1].plot(episodes, latency, label="average end-to-end latency")
    critical_latency = series(history, "criticalLoopDelay")
    if any(value is not None for value in critical_latency):
        axes[0, 1].plot(episodes, critical_latency, label="critical-task latency")
    axes[0, 1].set(title="Latency", xlabel="episode", ylabel="ms")
    axes[0, 1].legend()

    axes[1, 0].plot(episodes, series(history, "totalEnergy"), color="tab:orange")
    axes[1, 0].set(title="Total energy", xlabel="episode", ylabel="J")
    axes[1, 0].ticklabel_format(axis="y", style="sci", scilimits=(0, 0))

    success = series(history, "criticalDeadlineSuccessRate")
    migrations = series(history, "acceptedMigrations")
    if any(value is not None for value in success):
        axes[1, 1].plot(
            episodes,
            [100 * value if value is not None else None for value in success],
            color="tab:green",
            label="critical on-time rate",
        )
        axes[1, 1].set_ylabel("on-time tasks (%)")
    migration_axis = axes[1, 1].twinx()
    migration_axis.plot(
        episodes, migrations, color="tab:purple", alpha=0.7, label="accepted migrations",
    )
    migration_axis.set_ylabel("migrations")
    axes[1, 1].set(title="Critical QoS and migrations", xlabel="episode")

    output.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(output, dpi=160)
    print(f"Wrote {output}")
    if show:
        plt.show()
    plt.close(figure)


def main() -> None:
    parser = argparse.ArgumentParser(description="Plot shared-PPO convergence metrics")
    parser.add_argument("--input", type=Path, default=None)
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--rolling-window", type=int, default=5)
    parser.add_argument("--show", action="store_true")
    args = parser.parse_args()

    if args.input is None:
        run_dir = latest_single_dir("shared_ppo")
        if run_dir is None:
            raise SystemExit("No single run found under agents/results/<date>/single/")
        candidates = [run_dir / "convergence.json", run_dir / "shared_ppo_convergence.json"]
        args.input = next((path for path in candidates if path.exists()), candidates[0])
        args.output = args.output or (run_dir / "training.png")
    elif args.output is None:
        args.output = args.input.with_name("training.png")

    with args.input.open(encoding="utf-8") as handle:
        history = json.load(handle)
    if not isinstance(history, list) or not history:
        raise SystemExit(f"No episode history in {args.input}")
    plot(history, args.output, args.show, max(1, args.rolling_window))


if __name__ == "__main__":
    main()
