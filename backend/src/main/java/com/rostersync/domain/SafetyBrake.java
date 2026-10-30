package com.rostersync.domain;

/**
 * A nightly export that is missing a large share of active employees is almost always a
 * broken export (truncated file, failed join upstream), not a mass exit. The brake stops
 * the run before anything is applied and waits for a human.
 */
public final class SafetyBrake {

    public record Verdict(boolean tripped, double missingRatio, double threshold) {}

    private final double threshold;

    public SafetyBrake(double threshold) {
        if (!(threshold > 0 && threshold <= 1)) throw new IllegalArgumentException("Safety brake threshold must be in (0, 1]");
        this.threshold = threshold;
    }

    public double threshold() {
        return threshold;
    }

    public Verdict evaluate(long activeCount, long missingCount) {
        double ratio = activeCount > 0 ? (double) missingCount / activeCount : 0;
        boolean tripped = activeCount > 0 && ratio >= threshold;
        return new Verdict(tripped, ratio, threshold);
    }
}
