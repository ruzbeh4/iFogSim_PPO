"""
genetic.py
──────────
Genetic Algorithm (GA) placement agent.

The GA searches for a globally optimal initial microservice placement that
minimises a combined cost function:

    fitness = w_latency * latency_cost + w_energy * energy_cost

Encoding
────────
A *chromosome* is a flat list of device indices (into the candidate list),
one index per (request × module) pair.  For example, with 3 requests each
having 2 pending modules, the chromosome has 6 genes.

    chromosome = [dev_idx_r0m0, dev_idx_r0m1, dev_idx_r1m0, ...]

Cost function
─────────────
latency_cost  – approximated by the hierarchy level of the chosen device:
                level 0 (cloud) = high latency, level 1 (fog) = medium,
                level 2 (edge) = low.  Weighted by how latency-sensitive
                each module is (actuator_controller > smart_analyzer).

energy_cost   – proportional to the MIPS fraction consumed on each device:
                (committed_mips / available_mips).  Concentrating load on
                fewer devices wastes energy; distributing it saves power.

GA parameters (all tunable at the top of the class)
────────────────────────────────────────────────────
POP_SIZE        population size
MAX_GENERATIONS maximum number of evolutionary cycles
MUTATION_RATE   probability of randomly reassigning one gene
ELITE_FRAC      fraction of top individuals kept unchanged (elitism)
TOURNAMENT_K    candidates drawn for tournament selection

@author M-H-Boroumandnia
"""

import random
import copy
from .base_agent import BasePlacementAgent


# Latency sensitivity weights per module name (higher = more sensitive)
# Modules not listed here get weight 1.0
LATENCY_WEIGHTS: dict[str, float] = {
    "actuator_controller": 3.0,  # control commands are time-critical
    "smart_analyzer":      1.5,  # inference can tolerate slightly more latency
    "data_preprocessor":   1.0,  # already at edge; weight not used in practice
}

# One-way latency (ms) from the IoT device to a module at each hierarchy level.
# Matches the topology constants in IndustrialIoTSimulation.java:
#   IOT_TO_GW_LATENCY = 20 ms,  GW_TO_CLOUD_LATENCY = 100 ms
# Cloud costs 120 ms (two hops); fog costs 20 ms (one hop); IoT costs 0 ms.
LEVEL_LATENCY_MS: dict[int, float] = {0: 120.0, 1: 20.0, 2: 0.0}

# Fitness component weights
W_LATENCY = 0.6
W_ENERGY  = 0.4


class GeneticAgent(BasePlacementAgent):
    """Global placement optimiser based on a Genetic Algorithm."""

    # ── Tuneable GA hyper-parameters ──────────────────────────────────
    POP_SIZE        = 50
    MAX_GENERATIONS = 100
    MUTATION_RATE   = 0.1
    ELITE_FRAC      = 0.1   # top 10 % survive unchanged
    TOURNAMENT_K    = 5     # tournament selection pool size

    def decide(self, state: dict) -> dict:
        """
        Runs the Genetic Algorithm and returns the best placement found.

        Parameters
        ----------
        state : dict
            Simulation state from the Java bridge.

        Returns
        -------
        dict
            Action: {"placement": {"<requestId>": {"<module>": <deviceId>}}}
        """
        candidates = self.candidate_devices(state)
        if not candidates:
            return self.build_action({})

        # Build a flat ordered list of (request, module) slots that need placing
        slots: list[tuple[dict, dict]] = []  # [(request_dict, module_dict), ...]
        for req in state["requests"]:
            for mod in req["pendingModules"]:
                slots.append((req, mod))

        if not slots:
            return self.build_action({})

        num_genes = len(slots)
        num_devs  = len(candidates)

        # ── Initialise population ──────────────────────────────────────
        population = [
            [random.randint(0, num_devs - 1) for _ in range(num_genes)]
            for _ in range(self.POP_SIZE)
        ]

        best_chromosome = None
        best_fitness    = float("inf")

        elite_size = max(1, int(self.POP_SIZE * self.ELITE_FRAC))

        for generation in range(self.MAX_GENERATIONS):
            # ── Evaluate fitness ──────────────────────────────────────
            scored = [
                (self._fitness(chromo, slots, candidates), chromo)
                for chromo in population
            ]
            scored.sort(key=lambda x: x[0])

            gen_best_fitness, gen_best_chromo = scored[0]
            if gen_best_fitness < best_fitness:
                best_fitness    = gen_best_fitness
                best_chromosome = copy.copy(gen_best_chromo)

            # ── Elitism: carry top individuals forward unchanged ───────
            next_gen = [copy.copy(c) for _, c in scored[:elite_size]]

            # ── Fill rest via selection + crossover + mutation ─────────
            while len(next_gen) < self.POP_SIZE:
                parent_a = self._tournament_select(scored)
                parent_b = self._tournament_select(scored)
                child    = self._crossover(parent_a, parent_b)
                child    = self._mutate(child, num_devs)
                next_gen.append(child)

            population = next_gen

        # ── Decode best chromosome into placement map ──────────────────
        placement_map = self._decode(best_chromosome, slots, candidates)

        print(f"[GA] Best fitness after {self.MAX_GENERATIONS} generations: "
              f"{best_fitness:.4f}")
        return self.build_action(placement_map)

    # ------------------------------------------------------------------
    # GA operators
    # ------------------------------------------------------------------

    def _fitness(
        self,
        chromosome: list[int],
        slots: list[tuple[dict, dict]],
        candidates: list[dict],
    ) -> float:
        """
        Evaluates the combined latency + energy cost of a chromosome.

        A chromosome that overloads a device is penalised with a large
        infeasibility penalty so the GA avoids constraint violations.
        """
        # Track load committed by this chromosome (separate from live state)
        load: dict[int, float] = {d["id"]: 0.0 for d in candidates}

        latency_cost = 0.0
        energy_cost  = 0.0
        infeasible   = 0.0

        for gene, (req, mod) in zip(chromosome, slots):
            device    = candidates[gene]
            dev_id    = device["id"]
            req_mips  = mod["requiredMips"]
            req_ram   = mod["requiredRam"]

            # Latency component: cloud (level 0) = 120 ms from IoT,
            # fog (level 1) = 20 ms.  Higher real latency → higher cost.
            lw = LATENCY_WEIGHTS.get(mod["name"], 1.0)
            latency_cost += lw * LEVEL_LATENCY_MS.get(device["level"], 120.0)

            # Energy component: load fraction after adding this module
            load[dev_id] += req_mips
            avail_mips    = max(device["availableMips"], 1.0)  # avoid /0
            energy_cost  += load[dev_id] / avail_mips

            # Infeasibility penalty for exceeding MIPS or RAM
            if load[dev_id] > device["availableMips"]:
                infeasible += (load[dev_id] - device["availableMips"]) / avail_mips * 10
            if req_ram > device["availableRam"]:
                infeasible += 1.0

        return W_LATENCY * latency_cost + W_ENERGY * energy_cost + infeasible

    def _tournament_select(
        self, scored: list[tuple[float, list[int]]]
    ) -> list[int]:
        """Returns the chromosome with the lowest fitness from a random pool."""
        pool = random.sample(scored, min(self.TOURNAMENT_K, len(scored)))
        return copy.copy(min(pool, key=lambda x: x[0])[1])

    @staticmethod
    def _crossover(parent_a: list[int], parent_b: list[int]) -> list[int]:
        """Single-point crossover: split at a random locus."""
        if len(parent_a) <= 1:
            return copy.copy(parent_a)
        point = random.randint(1, len(parent_a) - 1)
        return parent_a[:point] + parent_b[point:]

    def _mutate(self, chromosome: list[int], num_devs: int) -> list[int]:
        """Randomly reassigns each gene with probability MUTATION_RATE."""
        return [
            random.randint(0, num_devs - 1) if random.random() < self.MUTATION_RATE else gene
            for gene in chromosome
        ]

    # ------------------------------------------------------------------
    # Decoding
    # ------------------------------------------------------------------

    @staticmethod
    def _decode(
        chromosome: list[int],
        slots: list[tuple[dict, dict]],
        candidates: list[dict],
    ) -> dict[int, dict[str, int]]:
        """
        Converts a chromosome back to {requestId: {moduleName: deviceId}}.
        """
        placement_map: dict[int, dict[str, int]] = {}
        for gene, (req, mod) in zip(chromosome, slots):
            req_id = req["requestId"]
            if req_id not in placement_map:
                placement_map[req_id] = {}
            placement_map[req_id][mod["name"]] = candidates[gene]["id"]
        return placement_map
