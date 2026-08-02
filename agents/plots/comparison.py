"""Plot comparative charts for placement / migration agents."""

from __future__ import annotations

import argparse
from pathlib import Path

from utils.plotting import EPISODE_FILE_RE, configure_matplotlib, mean, metric
from utils.results_paths import latest_compare_dir

# Preferred order when present; unknown folders are appended alphabetically.
KNOWN_AGENTS = (
    "heuristic",
    "genetic",
    "genetic_heuristic",
    "ppo",
    "heuristic_heuristic",
    "heuristic_ppo",
    "heur_v2_heuristic",
    "heur_v2_ppo",
)
COLORS = {
    "heuristic": "tab:blue",
    "genetic": "tab:orange",
    "genetic_heuristic": "tab:red",
    "ppo": "tab:green",
    "heuristic_heuristic": "tab:purple",
    "heuristic_ppo": "tab:cyan",
    "heur_v2_heuristic": "tab:purple",
    "heur_v2_ppo": "tab:cyan",
}
STYLES = {
    "heuristic": {"linestyle": "-", "marker": "o", "markersize": 5, "alpha": 0.95, "zorder": 3},
    "genetic": {"linestyle": "--", "marker": "s", "markersize": 4, "alpha": 0.9, "zorder": 2},
    "genetic_heuristic": {"linestyle": ":", "marker": "D", "markersize": 4, "alpha": 0.95, "zorder": 3},
    "ppo": {"linestyle": "-.", "marker": "^", "markersize": 4, "alpha": 0.9, "zorder": 4},
    "heuristic_heuristic": {"linestyle": "-", "marker": "v", "markersize": 4, "alpha": 0.95, "zorder": 3},
    "heuristic_ppo": {"linestyle": "-.", "marker": "P", "markersize": 4, "alpha": 0.9, "zorder": 4},
    "heur_v2_heuristic": {"linestyle": "-", "marker": "v", "markersize": 4, "alpha": 0.95, "zorder": 3},
    "heur_v2_ppo": {"linestyle": "-.", "marker": "P", "markersize": 4, "alpha": 0.9, "zorder": 4},
}
LABELS = {
    "heuristic": "heuristic",
    "genetic": "genetic",
    "genetic_heuristic": "GA + Heuristic",
    "ppo": "GA + PPO",
    "heuristic_heuristic": "Heuristic + Heuristic",
    "heuristic_ppo": "Heuristic + PPO",
    "heur_v2_heuristic": "Heuristic-v2 + Heuristic",
    "heur_v2_ppo": "Heuristic-v2 + PPO",
}


def discover_agents(results_root: Path) -> list[str]:
    found = {
        path.name
        for path in results_root.iterdir()
        if path.is_dir() and path.name != "plots" and any(path.glob("episode_*.json"))
    }
    ordered = [name for name in KNOWN_AGENTS if name in found]
    ordered.extend(sorted(found - set(ordered)))
    return ordered


def load_agent_history(agent_dir: Path) -> list[dict]:
    import json

    latest: dict[int, tuple[str, dict]] = {}
    for path in sorted(agent_dir.glob("episode_*.json")):
        match = EPISODE_FILE_RE.search(path.name)
        if not match:
            continue
        seed = int(match.group("seed"))
        stamp = match.group("stamp")
        payload = json.loads(path.read_text(encoding="utf-8"))
        payload["episode"] = seed
        prev = latest.get(seed)
        if prev is None or stamp >= prev[0]:
            latest[seed] = (stamp, payload)
    return [latest[seed][1] for seed in sorted(latest)]


def critical_success_pct(payload: dict) -> float | None:
    rate = payload.get("criticalDeadlineSuccessRate")
    return None if rate is None else float(rate) * 100.0


def summarize(history: list[dict]) -> dict[str, float | None]:
    latency = [v for v in (metric(item, "averageLatency") for item in history) if v is not None]
    energy = [v for v in (metric(item, "totalEnergy") for item in history) if v is not None]
    migrations = [v for v in (metric(item, "migrationCount") for item in history) if v is not None]
    success = [v for v in (critical_success_pct(item) for item in history) if v is not None]
    reward = [v for v in (metric(item, "meanLocalReward") for item in history) if v is not None]
    return {
        "episodes": float(len(history)),
        "averageLatency": mean(latency),
        "totalEnergy": mean(energy),
        "migrationCount": mean(migrations),
        "criticalSuccessPct": mean(success),
        "meanLocalReward": mean(reward),
    }


def plot(histories: dict[str, list[dict]], output: Path, show: bool) -> None:
    plt = configure_matplotlib(show)
    agents = [name for name in histories if histories[name]]
    figure, axes = plt.subplots(2, 3, figsize=(14, 8))
    title = " vs ".join(LABELS.get(name, name) for name in agents) or "Agent comparison"
    figure.suptitle(title)

    def plot_metric(ax, key=None, transform=None, title="", ylabel=""):
        for agent in agents:
            history = histories.get(agent) or []
            if not history:
                continue
            xs = [item["episode"] for item in history]
            if key is None:
                ys = [transform(item) for item in history]
            else:
                ys = [metric(item, key) for item in history]
            style = STYLES.get(agent, {"linestyle": "-", "marker": ".", "markersize": 4, "alpha": 0.9})
            ax.plot(
                xs, ys,
                label=LABELS.get(agent, agent),
                color=COLORS.get(agent, None),
                **style,
            )
        ax.set(title=title, xlabel="episode seed", ylabel=ylabel)
        ax.legend()
        ax.grid(True, alpha=0.3)

    plot_metric(axes[0, 0], key="averageLatency", title="Average Latency", ylabel="ms")
    plot_metric(axes[0, 1], key="totalEnergy", title="Energy", ylabel="J")
    axes[0, 1].set_ylim(1.0e7, 2.5e7)
    plot_metric(axes[0, 2], key="migrationCount", title="Service Migration Count", ylabel="count")
    plot_metric(
        axes[1, 0],
        transform=critical_success_pct,
        title="Task Success Rate (Critical)",
        ylabel="%",
    )

    ax = axes[1, 1]
    labels = []
    latencies = []
    colors = []
    for agent in agents:
        summary = summarize(histories.get(agent, []))
        if summary["averageLatency"] is None:
            continue
        labels.append(LABELS.get(agent, agent))
        latencies.append(summary["averageLatency"])
        colors.append(COLORS.get(agent, "tab:gray"))
    if labels:
        ax.bar(labels, latencies, color=colors)
    ax.set(title="Mean Latency Summary", ylabel="ms")
    ax.grid(True, axis="y", alpha=0.3)

    axes[1, 2].set_visible(False)

    figure.tight_layout()
    output.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(output, dpi=160)
    print(f"Wrote {output}")

    print("\nMean metrics:")
    for agent in agents:
        summary = summarize(histories.get(agent, []))
        print(
            f"  {LABELS.get(agent, agent):14s}  n={int(summary['episodes'] or 0):2d}  "
            f"lat={summary['averageLatency']}  "
            f"energy={summary['totalEnergy']}  "
            f"mig={summary['migrationCount']}  "
            f"crit%={summary['criticalSuccessPct']}  "
            f"reward={summary['meanLocalReward']}"
        )

    if show:
        plt.show()
    plt.close(figure)


def main() -> None:
    parser = argparse.ArgumentParser(description="Plot agent comparison charts")
    parser.add_argument("--results-root", type=Path, default=None)
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--show", action="store_true")
    args = parser.parse_args()

    results_root = args.results_root or latest_compare_dir()
    if results_root is None:
        raise SystemExit("No comparison run found under agents/results/<date>/compare/")
    results_root = Path(results_root)
    output = args.output or (results_root / "plots" / "agent_comparison.png")

    agents = discover_agents(results_root)
    histories: dict[str, list[dict]] = {}
    for agent in agents:
        agent_dir = results_root / agent
        histories[agent] = load_agent_history(agent_dir)
        print(f"{agent}: {len(histories[agent])} episodes in {agent_dir}")

    if not any(histories.values()):
        raise SystemExit(f"No comparison episode JSON found under {results_root}")
    plot(histories, output, args.show)


if __name__ == "__main__":
    main()
