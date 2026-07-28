package org.fog.utils.distribution;

import java.util.Random;

/**
 * Reproducible incident-driven inter-arrival process for rare but bursty
 * critical events. Outside incidents it behaves like a low-rate background
 * alarm stream; during incidents it produces short bursts.
 */
public class SeededIncidentDistribution extends Distribution {
    private final Random random;
    private final double backgroundMean;
    private final double incidentMean;
    private final double incidentGapMean;
    private final double incidentDurationMin;
    private final double incidentDurationMax;

    private double currentTime = 0.0;
    private double nextIncidentStart;
    private double currentIncidentEnd = Double.NEGATIVE_INFINITY;

    public SeededIncidentDistribution(double backgroundMean,
                                      double incidentMean,
                                      double incidentGapMean,
                                      double incidentDurationMin,
                                      double incidentDurationMax,
                                      long seed) {
        if (backgroundMean <= 0.0 || incidentMean <= 0.0 || incidentGapMean <= 0.0
                || incidentDurationMin <= 0.0 || incidentDurationMax < incidentDurationMin) {
            throw new IllegalArgumentException("Invalid incident distribution parameters");
        }
        this.backgroundMean = backgroundMean;
        this.incidentMean = incidentMean;
        this.incidentGapMean = incidentGapMean;
        this.incidentDurationMin = incidentDurationMin;
        this.incidentDurationMax = incidentDurationMax;
        this.random = new Random(seed);
        this.nextIncidentStart = sampleExponential(incidentGapMean);
    }

    @Override
    public double getNextValue() {
        if (currentTime >= currentIncidentEnd && currentTime >= nextIncidentStart) {
            startNextIncident();
        }

        double delta;
        if (currentTime >= nextIncidentStart && currentTime < currentIncidentEnd) {
            delta = sampleExponential(incidentMean);
        } else {
            delta = sampleExponential(backgroundMean);
            if (currentTime + delta > nextIncidentStart) {
                delta = Math.max(1e-6, nextIncidentStart - currentTime);
            }
        }

        currentTime += delta;
        if (currentTime >= currentIncidentEnd && currentTime >= nextIncidentStart) {
            startNextIncident();
        }
        return delta;
    }

    @Override
    public int getDistributionType() {
        return 3;
    }

    @Override
    public double getMeanInterTransmitTime() {
        double meanDuration = (incidentDurationMin + incidentDurationMax) / 2.0;
        double expectedBackgroundEvents = incidentGapMean / backgroundMean;
        double expectedIncidentEvents = meanDuration / incidentMean;
        double expectedEvents = Math.max(1e-6, expectedBackgroundEvents + expectedIncidentEvents);
        return (incidentGapMean + meanDuration) / expectedEvents;
    }

    private void startNextIncident() {
        double duration = incidentDurationMin + random.nextDouble() * (incidentDurationMax - incidentDurationMin);
        currentIncidentEnd = nextIncidentStart + duration;
        nextIncidentStart = currentIncidentEnd + sampleExponential(incidentGapMean);
    }

    private double sampleExponential(double mean) {
        return -mean * Math.log(1.0 - random.nextDouble());
    }
}
