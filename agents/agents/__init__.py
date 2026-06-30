"""
Placement agents package.

Available agents
----------------
HeuristicAgent  – Least-loaded (Shortest-Queue) baseline
GeneticAgent    – Global optimisation via a Genetic Algorithm
PPOAgent        – Step-loop bridge placeholder for a trained PPO/DRL policy
                   (see ppo.py docstring for what to implement)
"""

from .heuristic import HeuristicAgent
from .genetic   import GeneticAgent
from .ppo       import PPOAgent

__all__ = ["HeuristicAgent", "GeneticAgent", "PPOAgent"]
