"""
Placement agents package.

Available agents
----------------
HeuristicAgent  – Least-loaded (Shortest-Queue) baseline
GeneticAgent    – Global optimisation via a Genetic Algorithm
"""

from .heuristic import HeuristicAgent
from .genetic   import GeneticAgent

__all__ = ["HeuristicAgent", "GeneticAgent"]
