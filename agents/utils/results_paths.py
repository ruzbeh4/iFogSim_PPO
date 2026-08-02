"""Dated result directories: results/<YYYY-MM-DD>/{single|compare}/<run_name>/."""

from __future__ import annotations

from datetime import date
from pathlib import Path

PACKAGE_ROOT = Path(__file__).resolve().parents[1]
RESULTS_ROOT = PACKAGE_ROOT / "results"
MODEL_FILENAME = "model.pth"


def make_run_dir(mode: str, name: str, day: str | None = None) -> Path:
    if mode not in ("single", "compare"):
        raise ValueError(f"mode must be 'single' or 'compare', got {mode!r}")
    day = day or date.today().isoformat()
    path = RESULTS_ROOT / day / mode / name
    path.mkdir(parents=True, exist_ok=True)
    return path


def model_path_for(run_dir: Path | str) -> Path:
    return Path(run_dir) / MODEL_FILENAME


def _newest_dir(paths: list[Path]) -> Path | None:
    if not paths:
        return None
    return max(paths, key=lambda path: path.stat().st_mtime)


def latest_compare_dir() -> Path | None:
    if not RESULTS_ROOT.is_dir():
        return None
    candidates: list[Path] = []
    for day_dir in RESULTS_ROOT.glob("*"):
        compare_root = day_dir / "compare"
        if not compare_root.is_dir():
            continue
        candidates.extend(path for path in compare_root.iterdir() if path.is_dir())
    return _newest_dir(candidates)


def latest_single_dir(name_prefix: str = "shared_ppo") -> Path | None:
    if not RESULTS_ROOT.is_dir():
        return None
    candidates: list[Path] = []
    for day_dir in RESULTS_ROOT.glob("*"):
        single_root = day_dir / "single"
        if not single_root.is_dir():
            continue
        candidates.extend(
            path for path in single_root.iterdir()
            if path.is_dir() and path.name.startswith(name_prefix)
        )
    return _newest_dir(candidates)


def latest_model_path(name_prefix: str = "shared_ppo") -> Path | None:
    run_dir = latest_single_dir(name_prefix)
    if run_dir is None:
        return None
    path = model_path_for(run_dir)
    return path if path.is_file() else None
