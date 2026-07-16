package org.fog.utils.distribution;

import java.util.Random;

/** Reproducible uniform distribution for scenario-level workload parameters. */
public class SeededUniformDistribution extends Distribution {
    private final double min;
    private final double max;
    private final Random random;

    public SeededUniformDistribution(double min, double max, long seed) {
        if (min <= 0.0 || max < min) throw new IllegalArgumentException("Invalid uniform range");
        this.min = min;
        this.max = max;
        this.random = new Random(seed);
    }

    @Override
    public double getNextValue() {
        return min + random.nextDouble() * (max - min);
    }

    @Override
    public int getDistributionType() { return Distribution.UNIFORM; }

    @Override
    public double getMeanInterTransmitTime() { return (min + max) / 2.0; }
}
