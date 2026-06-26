package org.fog.utils.distribution;

import java.util.Random;

public class SeededExponentialDistribution extends Distribution {
    private double mean;
    private Random random;

    public SeededExponentialDistribution(double mean, long seed) {
        super();
        this.mean = mean;
        this.random = new Random(seed);
    }

    @Override
    public double getNextValue() {
        // Standard formula for exponential random variable
        return -mean * Math.log(1.0 - random.nextDouble());
    }

    @Override
    public int getDistributionType() {
        return 3; // 3 represents Exponential in iFogSim
    }

    @Override
    public double getMeanInterTransmitTime() {
        return mean;
    }
}