package org.enthusia.playtime.activity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final double HEARTBEAT_BASE_SCORE = 0.98D;
    private static final double HEARTBEAT_SCORE_RANGE = 0.02D;
    private static final double MIN_ROTATION_AMOUNT = 2.0D;
    private static final int MOVEMENT_MINIMUM_PERIOD = 2;
    private static final int SEQUENCE_MINIMUM_PERIOD = 3;
    private static final int REQUIRED_STRUCTURE_CHANGES = 2;
    private static final float MEANINGFUL_TURN_DEGREES = 15.0F;
    private static final double DIRECTION_CHANGE_SIMILARITY = 0.75D;
    private static final double MIN_DIRECTION_DISTANCE = 0.02D;
    private static final double MIN_SEQUENCE_HORIZONTAL_DISTANCE = 0.25D;
    private static final int FIRST_CYCLE_OFFSET = 0;
    private static final double STRONG_SIGNAL_THRESHOLD = 0.80D;
    private static final int MULTI_SIGNAL_COUNT = 2;
    private static final double MULTI_SIGNAL_BONUS = 0.12D;
    private static final double EXTRA_SIGNAL_BONUS = 0.05D;
    private static final double CLICK_ONLY_FLOOR = 0.91D;
    private static final double EXTREME_REGULARITY_THRESHOLD = 0.985D;
    private static final double SEQUENCE_REGULARITY_THRESHOLD = 0.975D;
    private static final double CLICK_FLOOR = 0.88D;
    private static final double ROTATION_FLOOR = 0.90D;
    private static final double MOVEMENT_FLOOR = 0.90D;
    private static final double SEQUENCE_FLOOR = 0.96D;
    private static final double VARIATION_MAX_EVIDENCE = 0.30D;
    private static final long VARIATION_WINDOW_MILLIS = 10_000L;
    private static final int VARIATION_MINIMUM_SAMPLES = 8;
    private static final int VARIATION_DISTINCT_SIGNATURES = 2;
    private static final double VARIATION_MINIMUM_CV = 0.10D;
    private static final double MIN_DIRECTION_LENGTH = 0.01D;
    private static final int MEAN_INTERVAL_MINIMUM_SAMPLES = 2;
    private static final int CV_INTERVAL_MINIMUM_SAMPLES = 3;

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
        Regularity click = analyzeClick(bounded, nowMillis);
        Regularity rotation = analyzeRotation(bounded, nowMillis);
        CycleResult movement = analyzeMovement(bounded, nowMillis);
        CycleResult sequence = analyzeSequence(bounded, nowMillis);
        return buildAnalysis(bounded, nowMillis, clickOnlyRecently,
                click, rotation, movement, sequence);
    }

    private Regularity analyzeClick(List<BehaviorSample> samples, long nowMillis) {
        return settings.click().enabled() ? clickRegularity(samples, nowMillis) : Regularity.NONE;
    }

    private Regularity analyzeRotation(List<BehaviorSample> samples, long nowMillis) {
        return settings.rotation().enabled() ? rotationRegularity(samples, nowMillis) : Regularity.NONE;
    }

    private CycleResult analyzeMovement(List<BehaviorSample> samples, long nowMillis) {
        return settings.movement().enabled() ? movementRecurrence(samples, nowMillis) : CycleResult.NONE;
    }

    private CycleResult analyzeSequence(List<BehaviorSample> samples, long nowMillis) {
        if (!settings.sequence().enabled()) {
            return CycleResult.NONE;
        }
        return strongerCycle(sequenceRecurrence(samples, nowMillis), heartbeatRecurrence(samples, nowMillis));
    }

    private Analysis buildAnalysis(List<BehaviorSample> samples,
                                   long nowMillis,
                                   boolean clickOnlyRecently,
                                   Regularity click,
                                   Regularity rotation,
                                   CycleResult movement,
                                   CycleResult sequence) {
        double evidence = combinedEvidence(click, rotation, movement, sequence, clickOnlyRecently);
        boolean varied = evidence < VARIATION_MAX_EVIDENCE && convincingVariation(samples, nowMillis);
        CycleResult dominant = strongerCycle(movement, sequence);
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
            if (amount < MIN_ROTATION_AMOUNT) {
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
        Map<Integer, List<Long>> timesBySignature = collectHeartbeatTimes(samples, nowMillis);
        CycleResult best = CycleResult.NONE;
        for (List<Long> times : timesBySignature.values()) {
            best = strongerCycle(best, heartbeatCandidate(times));
        }
        return best;
    }

    private Map<Integer, List<Long>> collectHeartbeatTimes(List<BehaviorSample> samples, long nowMillis) {
        long cutoff = nowMillis - HEARTBEAT_WINDOW_MILLIS;
        Map<Integer, List<Long>> timesBySignature = new ConcurrentHashMap<>();
        for (BehaviorSample sample : samples) {
            if (!heartbeatSampleEligible(sample, cutoff)) {
                continue;
            }
            int signature = normalizedActionSignature(sample);
            appendHeartbeatTime(timesBySignature, signature, sample.timestampMillis());
        }
        return timesBySignature;
    }

    private boolean heartbeatSampleEligible(BehaviorSample sample, long cutoff) {
        return sample.patternEligible()
                && sample.timestampMillis() >= cutoff
                && heartbeatEligible(sample)
                && normalizedActionSignature(sample) != 0;
    }

    private static void appendHeartbeatTime(Map<Integer, List<Long>> timesBySignature,
                                            int signature,
                                            long timestampMillis) {
        timesBySignature.computeIfAbsent(signature, ignored -> new ArrayList<>()).add(timestampMillis);
    }

    private CycleResult heartbeatCandidate(List<Long> times) {
        if (times.size() < HEARTBEAT_MIN_SAMPLES) {
            return CycleResult.NONE;
        }
        long span = times.get(times.size() - 1) - times.get(0);
        double mean = meanInterval(times);
        if (span < HEARTBEAT_MIN_SPAN_MILLIS || mean < HEARTBEAT_MIN_INTERVAL_MILLIS) {
            return CycleResult.NONE;
        }
        double cv = coefficientOfVariationOfIntervals(times);
        if (!Double.isFinite(cv) || cv > HEARTBEAT_MAX_CV) {
            return CycleResult.NONE;
        }
        double score = HEARTBEAT_BASE_SCORE
                + HEARTBEAT_SCORE_RANGE * (1.0D - cv / HEARTBEAT_MAX_CV);
        return new CycleResult(clamp01(score), times.size(), Math.round(mean), 1);
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
        int minimumPeriod = movementOnly ? MOVEMENT_MINIMUM_PERIOD : SEQUENCE_MINIMUM_PERIOD;
        int maxPeriod = Math.min(maximumCycleSamples, size / minimumRepetitions);
        CycleResult best = CycleResult.NONE;
        for (int period = minimumPeriod; period <= maxPeriod; period++) {
            CycleResult candidate = evaluateCyclePeriod(samples, minimumRepetitions,
                    similarityThreshold, minimumCycleMillis, movementOnly, size, period);
            best = strongerCycle(best, candidate);
        }
        return best;
    }

    private CycleResult evaluateCyclePeriod(List<BehaviorSample> samples,
                                            int minimumRepetitions,
                                            double similarityThreshold,
                                            long minimumCycleMillis,
                                            boolean movementOnly,
                                            int size,
                                            int period) {
        int referenceStart = size - period;
        if (!cycleHasMeaningfulStructure(samples, referenceStart, period, movementOnly)) {
            return CycleResult.NONE;
        }
        int previousStart = referenceStart - period;
        if (previousStart < 0) {
            return CycleResult.NONE;
        }
        long cycleMillis = samples.get(referenceStart).timestampMillis()
                - samples.get(previousStart).timestampMillis();
        if (cycleMillis < minimumCycleMillis) {
            return CycleResult.NONE;
        }
        RepetitionMatch match = repetitionMatch(samples, previousStart, referenceStart,
                period, movementOnly, similarityThreshold);
        if (match.repetitions() < minimumRepetitions) {
            return CycleResult.NONE;
        }
        double average = match.similarityTotal() / match.repetitions();
        double confidence = repetitionConfidence(match.repetitions(), minimumRepetitions);
        return new CycleResult(average * confidence, match.repetitions(), cycleMillis, period);
    }

    private RepetitionMatch repetitionMatch(List<BehaviorSample> samples,
                                             int previousStart,
                                             int referenceStart,
                                             int period,
                                             boolean movementOnly,
                                             double similarityThreshold) {
        int repetitions = 1;
        double similarityTotal = 1.0D;
        for (int candidateStart = previousStart; candidateStart >= 0; candidateStart -= period) {
            double similarity = cycleSimilarity(samples, candidateStart, referenceStart, period, movementOnly);
            if (similarity < similarityThreshold) {
                break;
            }
            repetitions++;
            similarityTotal += similarity;
        }
        return new RepetitionMatch(repetitions, similarityTotal);
    }

    private static double repetitionConfidence(int repetitions, int minimumRepetitions) {
        return Math.min(1.0D, 0.90D + 0.025D * (repetitions - minimumRepetitions));
    }

    private boolean cycleHasMeaningfulStructure(List<BehaviorSample> samples, int start,
                                                int period, boolean movementOnly) {
        CycleStructure structure = summarizeCycleStructure(samples, start, period);
        if (movementOnly) {
            return hasMovementStructure(structure);
        }
        return hasSequenceStructure(structure);
    }

    private CycleStructure summarizeCycleStructure(List<BehaviorSample> samples, int start, int period) {
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
            if (current.turnAmount() >= MEANINGFUL_TURN_DEGREES) {
                meaningfulTurns++;
            }
            BehaviorSample next = samples.get(start + ((index + 1) % period));
            if (isDirectionChange(current, next)) {
                directionChanges++;
            }
        }
        return new CycleStructure(distinctSignatures.size(), directionChanges,
                meaningfulTurns, horizontalTotal);
    }

    private static boolean isDirectionChange(BehaviorSample current, BehaviorSample next) {
        return directionSimilarity(current, next) < DIRECTION_CHANGE_SIMILARITY
                && current.horizontalDistance() > MIN_DIRECTION_DISTANCE
                && next.horizontalDistance() > MIN_DIRECTION_DISTANCE;
    }

    private static boolean hasMovementStructure(CycleStructure structure) {
        return structure.directionChanges() >= REQUIRED_STRUCTURE_CHANGES
                || structure.meaningfulTurns() >= REQUIRED_STRUCTURE_CHANGES;
    }

    private static boolean hasSequenceStructure(CycleStructure structure) {
        if (structure.distinctSignatures() >= REQUIRED_STRUCTURE_CHANGES) {
            return true;
        }
        return structure.horizontalTotal() > MIN_SEQUENCE_HORIZONTAL_DISTANCE
                && hasMovementStructure(structure);
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
            double timing = cycleTimingSimilarity(samples, candidateStart, referenceStart, offset);
            total += feature * 0.82D + timing * 0.18D;
        }
        return total / period;
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
        double evidence = baseEvidence(click, rotation, movement, sequence);
        evidence += strongSignalBonus(click, rotation, movement, sequence);
        evidence = applyClickEvidenceFloor(evidence, click, clickOnlyRecently);
        evidence = applyRotationEvidenceFloor(evidence, rotation);
        evidence = applyMovementEvidenceFloor(evidence, movement);
        evidence = applySequenceEvidenceFloor(evidence, sequence);
        return clamp01(evidence);
    }

    private static double baseEvidence(Regularity click,
                                       Regularity rotation,
                                       CycleResult movement,
                                       CycleResult sequence) {
        return click.score() * 0.40D
                + rotation.score() * 0.30D
                + movement.score() * 0.58D
                + sequence.score() * 0.68D;
    }

    private static double strongSignalBonus(Regularity click,
                                            Regularity rotation,
                                            CycleResult movement,
                                            CycleResult sequence) {
        int strongSignals = countStrongSignals(click.score(), rotation.score(),
                movement.score(), sequence.score());
        if (strongSignals < MULTI_SIGNAL_COUNT) {
            return 0.0D;
        }
        return MULTI_SIGNAL_BONUS + (strongSignals - MULTI_SIGNAL_COUNT) * EXTRA_SIGNAL_BONUS;
    }

    private static int countStrongSignals(double... scores) {
        int count = 0;
        for (double score : scores) {
            if (score >= STRONG_SIGNAL_THRESHOLD) {
                count++;
            }
        }
        return count;
    }

    private double applyClickEvidenceFloor(double evidence, Regularity click, boolean clickOnlyRecently) {
        if (clickOnlyRecently && click.score() >= STRONG_SIGNAL_THRESHOLD) {
            evidence = Math.max(evidence, CLICK_ONLY_FLOOR);
        }
        if (click.score() >= EXTREME_REGULARITY_THRESHOLD
                && click.count() >= settings.click().minimumSwings() * MULTI_SIGNAL_COUNT) {
            evidence = Math.max(evidence, CLICK_FLOOR);
        }
        return evidence;
    }

    private double applyRotationEvidenceFloor(double evidence, Regularity rotation) {
        if (rotation.score() >= EXTREME_REGULARITY_THRESHOLD
                && rotation.count() >= settings.rotation().minimumSamples() * MULTI_SIGNAL_COUNT) {
            return Math.max(evidence, ROTATION_FLOOR);
        }
        return evidence;
    }

    private double applyMovementEvidenceFloor(double evidence, CycleResult movement) {
        if (movement.score() >= EXTREME_REGULARITY_THRESHOLD
                && movement.repetitions() >= settings.movement().minimumCycles() + 1) {
            return Math.max(evidence, MOVEMENT_FLOOR);
        }
        return evidence;
    }

    private double applySequenceEvidenceFloor(double evidence, CycleResult sequence) {
        if (sequence.score() >= SEQUENCE_REGULARITY_THRESHOLD
                && sequence.repetitions() >= settings.sequence().minimumRepetitions()) {
            return Math.max(evidence, SEQUENCE_FLOOR);
        }
        return evidence;
    }

    private boolean convincingVariation(List<BehaviorSample> samples, long nowMillis) {
        long cutoff = nowMillis - VARIATION_WINDOW_MILLIS;
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
        if (count < VARIATION_MINIMUM_SAMPLES) {
            return false;
        }
        if (distinctSignatures.size() >= VARIATION_DISTINCT_SIGNATURES) {
            return true;
        }
        double mean = distanceSum / count;
        if (mean <= EPSILON) {
            return false;
        }
        double variance = Math.max(0.0D, distanceSquaredSum / count - mean * mean);
        return Math.sqrt(variance) / mean >= VARIATION_MINIMUM_CV;
    }

    private static double directionSimilarity(BehaviorSample first, BehaviorSample second) {
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
        if (times.size() < MEAN_INTERVAL_MINIMUM_SAMPLES) return 0.0D;
        long total = 0L;
        for (int index = 1; index < times.size(); index++) {
            total += times.get(index) - times.get(index - 1);
        }
        return (double) total / (times.size() - 1);
    }

    private static double coefficientOfVariationOfIntervals(List<Long> times) {
        if (times.size() < CV_INTERVAL_MINIMUM_SAMPLES) return Double.MAX_VALUE;
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

    private record RepetitionMatch(int repetitions, double similarityTotal) { }

    private record CycleStructure(int distinctSignatures,
                                  int directionChanges,
                                  int meaningfulTurns,
                                  double horizontalTotal) { }
}
