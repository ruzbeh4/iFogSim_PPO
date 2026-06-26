package org.fog.application.selectivity;

import java.util.Random;

public class SeededSelectivity implements SelectivityModel {
    private double selectivity;
    private Random random;

    public SeededSelectivity(double selectivity, long seed) {
        this.selectivity = selectivity;
        this.random = new Random(seed);
    }

    @Override
    public boolean canSelect() {
        return random.nextDouble() <= selectivity;
    }

    @Override
    public double getMeanRate() {
        return selectivity;
    }

    @Override
    public double getMaxRate() {
        return selectivity;
    }

    public double getSelectivity() {
        return selectivity;
    }
}