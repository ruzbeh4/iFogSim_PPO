"""Plot shared-PPO training metrics saved by shared_train_server.py.

Examples:
    python agents/plot_shared_training.py
    python agents/plot_shared_training.py --show --rolling-window 5
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def rolling(values: list[float | None], window: int) -> list[float | None]:
    result: list[float | None] = []
    for index, value in enumerate(values):
        if value is None:
            result.append(None)
            continue
        recent = [item for item in values[max(0, index - window + 1):index + 1]
                  if item is not None]
        result.append(sum(recent) / len(recent) if recent else None)
    return result


def series(history: list[dict], key: str) -> list[float | None]:
    return [None if item.get(key) is None else float(item[key]) for item in history]


def plot(history: list[dict], output: Path, show: bool, window: int) -> None:
    # Saving plots must not require Tk/a desktop on Windows or CI.
    if not show:
        import matplotlib
        matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    episodes = [int(item.get("episode", index + 1)) for index, item in enumerate(history)]
    figure, axes = plt.subplots(2, 2, figsize=(13, 8), constrained_layout=True)
    figure.suptitle("Shared service-level PPO training")

    reward = series(history, "meanLocalReward")
    reward_label = "mean local reward"
    if all(value is None for value in reward):
        reward = series(history, "meanRewardTarget")
        reward_label = "TD target (legacy; not immediate reward)"
    axes[0, 0].plot(episodes, reward, alpha=0.35, label=reward_label)
    if window > 1:
        axes[0, 0].plot(episodes, rolling(reward, window), linewidth=2,
                        label=f"{window}-episode mean")
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
        axes[1, 1].plot(episodes, [100 * value if value is not None else None for value in success],
                        color="tab:green", label="critical on-time rate")
        axes[1, 1].set_ylabel("on-time tasks (%)")
    else:
        axes[1, 1].text(0.5, 0.6, "No deadline metric in this history.\nRecorded from new runs onward.",
                         ha="center", va="center", transform=axes[1, 1].transAxes)
    migration_axis = axes[1, 1].twinx()
    migration_axis.plot(episodes, migrations, color="tab:purple", alpha=0.7,
                        label="accepted migrations")
    migration_axis.set_ylabel("migrations")
    axes[1, 1].set(title="Critical QoS and migrations", xlabel="episode")

    output.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(output, dpi=160)
    print(f"Wrote {output}")
    if show:
        plt.show()


def main() -> None:
    root = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="Plot shared-PPO convergence metrics")
    parser.add_argument("--input", type=Path, default=root / "results" / "shared_ppo_convergence.json")
    parser.add_argument("--output", type=Path, default=root / "results" / "shared_ppo_training.png")
    parser.add_argument("--rolling-window", type=int, default=5)
    parser.add_argument("--show", action="store_true")
    args = parser.parse_args()
    with args.input.open(encoding="utf-8") as handle:
        history = json.load(handle)
    if not isinstance(history, list) or not history:
        raise SystemExit(f"No episode history in {args.input}")
    plot(history, args.output, args.show, max(1, args.rolling_window))


if __name__ == "__main__":
    main()
