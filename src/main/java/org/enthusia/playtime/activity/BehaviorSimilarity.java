package org.enthusia.playtime.activity;

import java.util.ArrayList;
import java.util.List;

/** Pure similarity/statistics helpers used by the bounded activity pattern analyzer. */
final class BehaviorSimilarity {
    private static final double EPSILON = 1.0E-9D;
    private static final double MIN_DIRECTION_LENGTH = 0.01D;
    private static final int MEAN_INTERVAL_MINIMUM_SAMPLES = 2;
    private static final int CV_INTERVAL_MINIMUM_SAMPLES = 3;
    private static final int FIRST_CYCLE_OFFSET = 0;

    private BehaviorSimilarity() {
    }

    static int normalizedActionSignature(BehaviorSample sample) {
        int signature = sample.semanticActions();
        if (sample.hasMovement()) signature |= BehaviorSample.MOVE;
        if (sample.hasRotation()) signature |= BehaviorSample.ROTATE;
        if (sample.has(BehaviorSample.JUMP)) signature |= BehaviorSample.JUMP;
        return signature;
    }

    static double cycleSimilarity(List<BehaviorSample> samples,
                                  int candidateStart,
                                  int referenceStart,
                                  int period,
                                  boolean movementOnly) {
        double total = 0.0D;
        for (int offset = 0; offset < period; offset++) {
            BehaviorSample candidate = samples.get(candidateStart + offset);
            BehaviorSample reference = samples.get(referenceStart + offset);
            double feature = movementOnly
                    ? movementSimilarity(candidate, reference)
                    : behaviorSimilarity(candidate, reference);
            double timing = cycleTimingSimilarity(samples, candidateStart, referenceStart, offset);
            total += feature * 0.82D + timing * 0.18D;
        }
        return total / period;
    }

    static double directionSimilarity(BehaviorSample first, BehaviorSample second) {
        double firstLength = first.horizontalDistance();
        double secondLength = second.horizontalDistance();
        if (firstLength <= MIN_DIRECTION_LENGTH && secondLength <= MIN_DIRECTION_LENGTH) {
            return 1.0D;
        }
        if (firstLength <= MIN_DIRECTION_LENGTH || secondLength <= MIN_DIRECTION_LENGTH) {
            return 0.0D;
        }
        double dot = (first.dx() * second.dx() + first.dz() * second.dz())
                / (firstLength * secondLength);
        return clamp01((Math.max(-1.0D, Math.min(1.0D, dot)) + 1.0D) / 2.0D);
    }

    static double meanInterval(List<Long> times) {
        if (times.size() < MEAN_INTERVAL_MINIMUM_SAMPLES) return 0.0D;
        long total = 0L;
        for (int index = 1; index < times.size(); index++) {
            total += times.get(index) - times.get(index - 1);
        }
        return (double) total / (times.size() - 1);
    }

    static double coefficientOfVariationOfIntervals(List<Long> times) {
        if (times.size() < CV_INTERVAL_MINIMUM_SAMPLES) return Double.MAX_VALUE;
        List<Double> intervals = new ArrayList<>(times.size() - 1);
        for (int index = 1; index < times.size(); index++) {
            intervals.add((double) (times.get(index) - times.get(index - 1)));
        }
        return coefficientOfVariation(intervals);
    }

    static double coefficientOfVariation(List<Double> values) {
        if (values.isEmpty()) return Double.MAX_VALUE;
        double total = 0.0D;
        for (double value : values) total += value;
        double mean = total / values.size();
        if (mean <= EPSILON) return Double.MAX_VALUE;
        double variance = 0.0D;
        for (double value : values) {
            double delta = value - mean;
            variance += delta * delta;
        }
        return Math.sqrt(variance / values.size()) / mean;
    }

    static double cvScore(double cv, double configuredMaximum) {
        if (!Double.isFinite(cv)) return 0.0D;
        double max = Math.max(0.001D, configuredMaximum);
        if (cv <= max) {
            return clamp01(1.0D - 0.25D * (cv / max));
        }
        if (cv >= max * 2.0D) {
            return 0.0D;
        }
        return clamp01(0.75D * (1.0D - (cv - max) / max));
    }

    static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static double cycleTimingSimilarity(List<BehaviorSample> samples,
                                                int candidateStart,
                                                int referenceStart,
                                                int offset) {
        if (offset == FIRST_CYCLE_OFFSET) {
            return 1.0D;
        }
        long candidateInterval = samples.get(candidateStart + offset).timestampMillis()
                - samples.get(candidateStart + offset - 1).timestampMillis();
        long referenceInterval = samples.get(referenceStart + offset).timestampMillis()
                - samples.get(referenceStart + offset - 1).timestampMillis();
        return timingSimilarity(candidateInterval, referenceInterval);
    }

    private static double movementSimilarity(BehaviorSample first, BehaviorSample second) {
        if (!first.hasMovement() || !second.hasMovement()) {
            return 0.0D;
        }
        double direction = directionSimilarity(first, second);
        double distance = relativeSimilarity(first.horizontalDistance(), second.horizontalDistance(), 0.08D);
        double vertical = absoluteSimilarity(first.dy(), second.dy(), 0.12D);
        double turn = absoluteSimilarity(first.yawDelta(), second.yawDelta(), 12.0D);
        double jump = first.has(BehaviorSample.JUMP) == second.has(BehaviorSample.JUMP) ? 1.0D : 0.0D;
        return direction * 0.34D + distance * 0.24D + vertical * 0.14D
                + turn * 0.18D + jump * 0.10D;
    }

    private static double behaviorSimilarity(BehaviorSample first, BehaviorSample second) {
        int firstSignature = normalizedActionSignature(first);
        int secondSignature = normalizedActionSignature(second);
        int union = firstSignature | secondSignature;
        double action = union == 0 ? 1.0D
                : (double) Integer.bitCount(firstSignature & secondSignature) / Integer.bitCount(union);

        double feature = action;
        double weight = 1.0D;
        if (first.hasMovement() || second.hasMovement()) {
            feature += movementSimilarity(first, second) * 1.4D;
            weight += 1.4D;
        }
        if (first.hasRotation() || second.hasRotation()) {
            double rotation = first.hasRotation() && second.hasRotation()
                    ? (absoluteSimilarity(first.yawDelta(), second.yawDelta(), 10.0D)
                    + absoluteSimilarity(first.pitchDelta(), second.pitchDelta(), 8.0D)) / 2.0D
                    : 0.0D;
            feature += rotation * 0.8D;
            weight += 0.8D;
        }
        return feature / weight;
    }

    private static double timingSimilarity(long first, long second) {
        if (first <= 0L || second <= 0L) {
            return first == second ? 1.0D : 0.0D;
        }
        double scale = Math.max(120.0D, Math.max(first, second) * 0.30D);
        return absoluteSimilarity(first, second, scale);
    }

    private static double relativeSimilarity(double first, double second, double floor) {
        double scale = Math.max(floor, Math.max(Math.abs(first), Math.abs(second)) * 0.20D);
        return absoluteSimilarity(first, second, scale);
    }

    private static double absoluteSimilarity(double first, double second, double tolerance) {
        return clamp01(1.0D - Math.abs(first - second) / Math.max(EPSILON, tolerance));
    }
}
