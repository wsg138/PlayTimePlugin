package org.enthusia.playtime.activity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure, bounded behavioral analysis. It intentionally looks for recurrence across
 * whole cycles rather than treating repetitive Minecraft actions as suspicious by
 * themselves. The tracker invokes this at a coarse interval, never for every raw
 * movement event.
 */
public final class ActivityPatternAnalyzer {
    private static final double EPSILON = 1.0E-9D;
    private static final int MAX_ANALYSIS_SAMPLES = 256;
    private static final long HEARTBEAT_WINDOW_MILLIS = 300_000L;
    private static final long HEARTBEAT_MIN_INTERVAL_MILLIS = 2_000L;
    private static final long HEARTBEAT_MIN_SPAN_MILLIS = 60_000L;
    private static final int HEARTBEAT_MIN_SAMPLES = 5;
    private static final double HEARTBEAT_MAX_CV = 0.02D;

    private final AdvancedDetectionSettings settings;

    public ActivityPatternAnalyzer(AdvancedDetectionSettings settings) {
        this.settings = settings;
    }

    public Analysis analyze(List<BehaviorSample> history, long nowMillis, boolean clickOnlyRecently) {
        if (history == null || history.isEmpty()) {
            return Analysis.EMPTY;
        }
        int first = Math.max(0, history.size() - MAX_ANALYSIS_SAMPLES);
        List<BehaviorSample> bounded = history.subList(first, history.size());

        Regularity click = settings.click().enabled()
                ? clickRegularity(bounded, nowMillis)
                : Regularity.NONE;
        Regularity rotation = settings.rotation().enabled()
                ? rotationRegularity(bounded, nowMillis)
                : Regularity.NONE;
        CycleResult movement = settings.movement().enabled()
                ? movementRecurrence(bounded, nowMillis)
                : CycleResult.NONE;
        CycleResult sequence = CycleResult.NONE;
        if (settings.sequence().enabled()) {
            sequence = strongerCycle(sequenceRecurrence(bounded, nowMillis),
                    heartbeatRecurrence(bounded, nowMillis));
        }

        double evidence = combinedEvidence(click, rotation, movement, sequence, clickOnlyRecently);
        boolean varied = evidence < 0.30D && convincingVariation(bounded, nowMillis);
        CycleResult dominant = sequence.score() >= movement.score() ? sequence : movement;
        return new Analysis(click.score(), movement.score(), rotation.score(), sequence.score(),
                evidence, Math.max(movement.repetitions(), sequence.repetitions()),
                dominant.cycleMillis(), varied, click.count(), rotation.count());
    }

    private Regularity clickRegularity(List<BehaviorSample> samples, long nowMillis) {
        List<Long> times = new ArrayList<>();
        long cutoff = nowMillis - settings.click().windowMillis();
        for (BehaviorSample sample : samples) {
            if (sample.timestampMillis() >= cutoff && sample.has(BehaviorSample.SWING)) {
                times.add(sample.timestampMillis());
            }
        }
        if (times.size() < settings.click().minimumSwings()) {
            return new Regularity(0.0D, times.size());
        }
        double cv = coefficientOfVariationOfIntervals(times);
        return new Regularity(cvScore(cv, settings.click().maxCv()), times.size());
    }

    private Regularity rotationRegularity(List<BehaviorSample> samples, long nowMillis) {
        List<Long> times = new ArrayList<>();
        List<Double> amounts = new ArrayList<>();
        long cutoff = nowMillis - settings.rotation().windowMillis();
        for (BehaviorSample sample : samples) {
            if (!sample.patternEligible() || sample.timestampMillis() < cutoff || !sample.hasRotation()) {
                continue;
            }
            double amount = sample.turnAmount();
            if (amount < 2.0D) {
                continue;
            }
            times.add(sample.timestampMillis());
            amounts.add(amount);
        }
        if (times.size() < settings.rotation().minimumSamples()) {
            return new Regularity(0.0D, times.size());
        }
        double interval = cvScore(coefficientOfVariationOfIntervals(times), settings.rotation().maxCv());
        double amount = cvScore(coefficientOfVariation(amounts), settings.rotation().maxCv());
        return new Regularity(Math.sqrt(interval * amount), times.size());
    }

    private CycleResult movementRecurrence(List<BehaviorSample> samples, long nowMillis) {
        List<BehaviorSample> movement = new ArrayList<>();
        long cutoff = nowMillis - settings.movement().windowMillis();
        for (BehaviorSample sample : samples) {
            if (sample.patternEligible() && sample.timestampMillis() >= cutoff && sample.hasMovement()) {
                movement.add(sample);
            }
        }
        if (movement.size() < settings.movement().minimumSamples()) {
            return CycleResult.NONE;
        }
        return bestCycle(movement, settings.movement().minimumCycles(),
                settings.movement().maximumCycleSamples(), settings.movement().similarityThreshold(),
                settings.movement().minimumCycleMillis(), true);
    }

    private CycleResult sequenceRecurrence(List<BehaviorSample> samples, long nowMillis) {
        List<BehaviorSample> eligible = new ArrayList<>();
        long cutoff = nowMillis - settings.sequence().windowMillis();
        for (BehaviorSample sample : samples) {
            if (sample.patternEligible() && sample.timestampMillis() >= cutoff) {
                eligible.add(sample);
            }
        }
        if (eligible.size() < settings.sequence().minimumActions()) {
            return CycleResult.NONE;
        }
        return bestCycle(eligible, settings.sequence().minimumRepetitions(),
                settings.sequence().maximumCycleSamples(), settings.sequence().similarityThreshold(),
                0L, false);
    }

    /**
     * Detects low-frequency single-input keepalive macros that ordinary short-window
     * cycle analysis intentionally ignores. Human chat/movement can be repetitive,
     * so this requires five samples, at least a minute of span, intervals of at least
     * two seconds, and extremely low timing variance.
     */
    private CycleResult heartbeatRecurrence(List<BehaviorSample> samples, long nowMillis) {
        long cutoff = nowMillis - HEARTBEAT_WINDOW_MILLIS;
        Map<Integer, List<Long>> timesBySignature = new HashMap<>();
        for (BehaviorSample sample : samples) {
            if (!sample.patternEligible() || sample.timestampMillis() < cutoff
                    || !heartbeatEligible(sample)) {
                continue;
            }
            int signature = normalizedActionSignature(sample);
            if (signature == 0) {
                continue;
            }
            timesBySignature.computeIfAbsent(signature, ignored -> new ArrayList<>())
                    .add(sample.timestampMillis());
        }

        CycleResult best = CycleResult.NONE;
        for (List<Long> times : timesBySignature.values()) {
            if (times.size() < HEARTBEAT_MIN_SAMPLES) {
                continue;
            }
            long span = times.get(times.size() - 1) - times.get(0);
            double mean = meanInterval(times);
            if (span < HEARTBEAT_MIN_SPAN_MILLIS || mean < HEARTBEAT_MIN_INTERVAL_MILLIS) {
                continue;
            }
            double cv = coefficientOfVariationOfIntervals(times);
            if (!Double.isFinite(cv) || cv > HEARTBEAT_MAX_CV) {
                continue;
            }
            double score = 0.98D + 0.02D * (1.0D - cv / HEARTBEAT_MAX_CV);
            CycleResult candidate = new CycleResult(clamp01(score), times.size(),
                    Math.round(mean), 1);
            best = strongerCycle(best, candidate);
        }
        return best;
    }

    private static boolean heartbeatEligible(BehaviorSample sample) {
        return sample.has(BehaviorSample.COMMAND)
                || sample.has(BehaviorSample.CHAT)
                || sample.has(BehaviorSample.SWING)
                || sample.has(BehaviorSample.JUMP)
                || sample.hasMovement()
                || sample.hasRotation();
    }

    private static CycleResult strongerCycle(CycleResult first, CycleResult second) {
        return second.score() > first.score() ? second : first;
    }

    private CycleResult bestCycle(List<BehaviorSample> samples,
                                  int minimumRepetitions,
                                  int maximumCycleSamples,
                                  double similarityThreshold,
                                  long minimumCycleMillis,
                                  boolean movementOnly) {
        int size = samples.size();
        int minimumPeriod = movementOnly ? 2 : 3;
        int maxPeriod = Math.min(maximumCycleSamples, size / minimumRepetitions);
        CycleResult best = CycleResult.NONE;

        for (int period = minimumPeriod; period <= maxPeriod; period++) {
            int referenceStart = size - period;
            if (!cycleHasMeaningfulStructure(samples, referenceStart, period, movementOnly)) {
                continue;
            }
            int previousStart = referenceStart - period;
            if (previousStart < 0) {
                continue;
            }
            long cycleMillis = samples.get(referenceStart).timestampMillis()
                    - samples.get(previousStart).timestampMillis();
            if (cycleMillis < minimumCycleMillis) {
                continue;
            }

            int repetitions = 1;
            double similarityTotal = 1.0D;
            for (int candidateStart = previousStart;
                 candidateStart >= 0;
                 candidateStart -= period) {
                double similarity = cycleSimilarity(samples, candidateStart, referenceStart, period, movementOnly);
                if (similarity < similarityThreshold) {
                    break;
                }
                repetitions++;
                similarityTotal += similarity;
            }
            if (repetitions < minimumRepetitions) {
                continue;
            }
            double average = similarityTotal / repetitions;
            double repetitionConfidence = Math.min(1.0D,
                    0.90D + 0.025D * (repetitions - minimumRepetitions));
            double score = average * repetitionConfidence;
            if (score > best.score()) {
                best = new CycleResult(score, repetitions, cycleMillis, period);
            }
        }
        return best;
    }

    private boolean cycleHasMeaningfulStructure(List<BehaviorSample> samples, int start,
                                                int period, boolean movementOnly) {
        Set<Integer> distinctSignatures = new HashSet<>();
        int directionChanges = 0;
        int meaningfulTurns = 0;
        double horizontalTotal = 0.0D;

        for (int index = 0; index < period; index++) {
            BehaviorSample current = samples.get(start + index);
            int semantic = normalizedActionSignature(current);
            if (semantic != 0) {
                distinctSignatures.add(semantic);
            }
            horizontalTotal += current.horizontalDistance();
            if (current.turnAmount() >= 15.0F) {
                meaningfulTurns++;
            }

            BehaviorSample next = samples.get(start + ((index + 1) % period));
            if (directionSimilarity(current, next) < 0.75D
                    && current.horizontalDistance() > 0.02D
                    && next.horizontalDistance() > 0.02D) {
                directionChanges++;
            }
        }

        if (movementOnly) {
            // Holding W, sprinting, swimming/flying momentum, or simply travelling in a
            // straight line is not a cycle. Require a real horizontal path/reversal or
            // a meaningful turn. Stationary jump spam is intentionally not enough.
            return directionChanges >= 2 || meaningfulTurns >= 2;
        }

        // Multiple Bukkit/Paper events can merge into one physical input sample. Count
        // distinct sample signatures, not the number of set bits inside one sample, so
        // held-use/build behavior does not look like a multi-action macro by itself.
        if (distinctSignatures.size() >= 2) {
            return true;
        }
        // A pure movement sequence can still be meaningful if it traces a real cycle.
        return horizontalTotal > 0.25D && (directionChanges >= 2 || meaningfulTurns >= 2);
    }

    private double cycleSimilarity(List<BehaviorSample> samples, int candidateStart,
                                   int referenceStart, int period, boolean movementOnly) {
        double total = 0.0D;
        for (int offset = 0; offset < period; offset++) {
            BehaviorSample candidate = samples.get(candidateStart + offset);
            BehaviorSample reference = samples.get(referenceStart + offset);
            double feature = movementOnly
                    ? movementSimilarity(candidate, reference)
                    : behaviorSimilarity(candidate, reference);
            double timing = 1.0D;
            if (offset > 0) {
                long candidateInterval = candidate.timestampMillis()
                        - samples.get(candidateStart + offset - 1).timestampMillis();
                long referenceInterval = reference.timestampMillis()
                        - samples.get(referenceStart + offset - 1).timestampMillis();
                timing = timingSimilarity(candidateInterval, referenceInterval);
            }
            total += feature * 0.82D + timing * 0.18D;
        }
        return total / period;
    }

    private double movementSimilarity(BehaviorSample first, BehaviorSample second) {
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

    private double behaviorSimilarity(BehaviorSample first, BehaviorSample second) {
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

    private int normalizedActionSignature(BehaviorSample sample) {
        int signature = sample.semanticActions();
        if (sample.hasMovement()) signature |= BehaviorSample.MOVE;
        if (sample.hasRotation()) signature |= BehaviorSample.ROTATE;
        if (sample.has(BehaviorSample.JUMP)) signature |= BehaviorSample.JUMP;
        return signature;
    }

    private double combinedEvidence(Regularity click,
                                    Regularity rotation,
                                    CycleResult movement,
                                    CycleResult sequence,
                                    boolean clickOnlyRecently) {
        double evidence = click.score() * 0.40D
                + rotation.score() * 0.30D
                + movement.score() * 0.58D
                + sequence.score() * 0.68D;

        int strongSignals = 0;
        if (click.score() >= 0.80D) strongSignals++;
        if (rotation.score() >= 0.80D) strongSignals++;
        if (movement.score() >= 0.80D) strongSignals++;
        if (sequence.score() >= 0.80D) strongSignals++;
        if (strongSignals >= 2) {
            evidence += 0.12D + (strongSignals - 2) * 0.05D;
        }

        if (clickOnlyRecently && click.score() >= 0.80D) {
            evidence = Math.max(evidence, 0.91D);
        }
        if (click.score() >= 0.985D
                && click.count() >= settings.click().minimumSwings() * 2) {
            evidence = Math.max(evidence, 0.88D);
        }
        if (rotation.score() >= 0.985D
                && rotation.count() >= settings.rotation().minimumSamples() * 2) {
            evidence = Math.max(evidence, 0.90D);
        }
        if (movement.score() >= 0.985D
                && movement.repetitions() >= settings.movement().minimumCycles() + 1) {
            evidence = Math.max(evidence, 0.90D);
        }
        if (sequence.score() >= 0.975D
                && sequence.repetitions() >= settings.sequence().minimumRepetitions()) {
            evidence = Math.max(evidence, 0.96D);
        }
        return clamp01(evidence);
    }

    private boolean convincingVariation(List<BehaviorSample> samples, long nowMillis) {
        long cutoff = nowMillis - 10_000L;
        int count = 0;
        Set<Integer> distinctSignatures = new HashSet<>();
        double distanceSum = 0.0D;
        double distanceSquaredSum = 0.0D;
        for (BehaviorSample sample : samples) {
            if (!sample.patternEligible() || sample.timestampMillis() < cutoff) {
                continue;
            }
            count++;
            int signature = normalizedActionSignature(sample);
            if (signature != 0) {
                distinctSignatures.add(signature);
            }
            double distance = sample.distance();
            distanceSum += distance;
            distanceSquaredSum += distance * distance;
        }
        if (count < 8) {
            return false;
        }
        if (distinctSignatures.size() >= 2) {
            return true;
        }
        double mean = distanceSum / count;
        if (mean <= EPSILON) {
            return false;
        }
        double variance = Math.max(0.0D, distanceSquaredSum / count - mean * mean);
        return Math.sqrt(variance) / mean >= 0.10D;
    }

    private static double directionSimilarity(BehaviorSample first, BehaviorSample second) {
        double firstLength = first.horizontalDistance();
        double secondLength = second.horizontalDistance();
        if (firstLength <= 0.01D && secondLength <= 0.01D) {
            return 1.0D;
        }
        if (firstLength <= 0.01D || secondLength <= 0.01D) {
            return 0.0D;
        }
        double dot = (first.dx() * second.dx() + first.dz() * second.dz())
                / (firstLength * secondLength);
        return clamp01((Math.max(-1.0D, Math.min(1.0D, dot)) + 1.0D) / 2.0D);
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

    private static double meanInterval(List<Long> times) {
        if (times.size() < 2) return 0.0D;
        long total = 0L;
        for (int index = 1; index < times.size(); index++) {
            total += times.get(index) - times.get(index - 1);
        }
        return (double) total / (times.size() - 1);
    }

    private static double coefficientOfVariationOfIntervals(List<Long> times) {
        if (times.size() < 3) return Double.MAX_VALUE;
        List<Double> intervals = new ArrayList<>(times.size() - 1);
        for (int index = 1; index < times.size(); index++) {
            intervals.add((double) (times.get(index) - times.get(index - 1)));
        }
        return coefficientOfVariation(intervals);
    }

    private static double coefficientOfVariation(List<Double> values) {
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

    private static double cvScore(double cv, double configuredMaximum) {
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

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public record Analysis(double clickRegularity,
                           double movementRepetition,
                           double rotationRepetition,
                           double sequenceRepetition,
                           double combinedEvidence,
                           int repetitions,
                           long dominantCycleMillis,
                           boolean convincingVariation,
                           int swingSamples,
                           int rotationSamples) {
        static final Analysis EMPTY = new Analysis(0.0D, 0.0D, 0.0D, 0.0D,
                0.0D, 0, 0L, false, 0, 0);
    }

    private record Regularity(double score, int count) {
        static final Regularity NONE = new Regularity(0.0D, 0);
    }

    private record CycleResult(double score, int repetitions, long cycleMillis, int period) {
        static final CycleResult NONE = new CycleResult(0.0D, 0, 0L, 0);
    }
}
