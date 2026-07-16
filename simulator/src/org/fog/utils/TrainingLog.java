package org.fog.utils;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Locale;

/** Console controls for the shared-policy training entry only. */
public final class TrainingLog {
    private static final PrintStream CONSOLE = System.out;
    private static final boolean SUMMARY = Boolean.parseBoolean(
            System.getProperty("ifogsim.log.summary", "true"));
    private static final boolean DECISIONS = Boolean.parseBoolean(
            System.getProperty("ifogsim.log.decisions", "false"));
    private static final boolean DIAGNOSTICS = Boolean.parseBoolean(
            System.getProperty("ifogsim.log.diagnostics", "false"));

    private TrainingLog() { }

    /** Call before CloudSim is initialized. Errors remain on System.err. */
    public static void configure() {
        if (!DIAGNOSTICS) System.setOut(new PrintStream(OutputStream.nullOutputStream()));
    }

    public static void decision(String message) {
        if (DECISIONS) CONSOLE.println("[decision] " + message);
    }

    public static void episodeSummary(long seed, double simulationTime,
                                      double totalEnergy, double cloudCost,
                                      Double loopDelay, double meanActorReward,
                                      int geneticPlacements, int acceptedMigrations,
                                      int rejectedMigrations, int criticalTasks,
                                      int criticalTasksEvaluated, int criticalTasksPending,
                                      int criticalTasksMissed, double criticalSuccessRate) {
        if (!SUMMARY) return;
        CONSOLE.println();
        CONSOLE.println("[trajectory] seed=" + seed
                + "  time=" + String.format(Locale.ROOT, "%.1f", simulationTime)
                + "  energy=" + String.format(Locale.ROOT, "%.2f", totalEnergy)
                + " J  cloudCost=" + String.format(Locale.ROOT, "%.4f", cloudCost));
        CONSOLE.println("[trajectory] avgLatency="
                + (loopDelay == null ? "n/a" : String.format(Locale.ROOT, "%.2f ms", loopDelay))
                + "  meanLocalReward=" + String.format(Locale.ROOT, "%.5f", meanActorReward)
                + "  GA placements=" + geneticPlacements
                + "  PPO migrations=" + acceptedMigrations
                + "  rejected=" + rejectedMigrations);
		CONSOLE.println("[trajectory] criticalTasks=" + criticalTasks
				+ "  evaluated=" + criticalTasksEvaluated
				+ "  pending=" + criticalTasksPending
				+ "  missedDeadline=" + criticalTasksMissed
				+ "  onTime=" + String.format(Locale.ROOT, "%.1f%%", criticalSuccessRate * 100.0));
		if (criticalTasksEvaluated >= 10 && criticalSuccessRate < 0.80) {
			CONSOLE.println("[trajectory] WARNING: critical-task success rate is poor; inspect placement and latency.");
		}
    }
}
