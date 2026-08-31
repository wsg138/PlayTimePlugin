package org.enthusia.playtime.activity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure, bounded behavioral analysis. It looks for repeated cycles, regular
 * cadences, and sustained low-variety action streams while keeping environmental
 * movement out of the trusted-input path. The tracker invokes this at a coarse
 * interval, never for every raw movement event.
 */
public final class ActivityPatternAnalyzer {
    private static final int MAX_ANALYSIS_SAMPLES = 256;
    private static final long HEARTBEAT_WINDOW_MILLIS = 300_000L;
    private static final long HEARTBEAT_MIN_INTERVAL_MILLIS = 2_000L;
    private static final long HEARTBEAT_MIN_SPAN_MILLIS = 60_000L;
    private static final int HEARTBEAT_MIN_SAMPLES = 5;
    private static final double HEARTBEAT_MAX_CV = 0.02D;
    private static final double HEARTBEAT_BASE_SCORE = 0.98D;
    private static final double HEARTBEAT_SCORE_RANGE = 0.02D;
    private static final long ACTION_FANOUT_DEDUP_MILLIS = 20L;
    private static final int CADENCE_ACTION_MASK = BehaviorSample.SEMANTIC_ACTIONS | BehaviorSample.JUMP;
    private static final long CADENCE_WINDOW_MILLIS = 60_000L;
    private static final long CADENCE_MIN_SPAN_MILLIS = 4_000L;
    private static final int CADENCE_MIN_SAMPLES = 10;
    private static final double CADENCE_MAX_CV = 0.20D;
    private static final double CADENCE_BASE_SCORE = 0.975D;
    private static final double CADENCE_SCORE_RANGE = 0.025D;
    private static final long MONOTONY_WINDOW_MILLIS = 60_000L;
    private static final long MONOTONY_MIN_SPAN_MILLIS = 20_000L;
    private static final int MONOTONY_MIN_SAMPLES = 12;
    private static final double MONOTONY_DOMINANT_RATIO = 0.90D;
    private static final double MONOTONY_SCORE = 0.985D;
    private static final double MONOTONY_MOVEMENT_DISTANCE = 0.15D;
    private static final float MONOTONY_ROTATION_DEGREES = 15.0F;
    private static final double MIN_ROTATION_AMOUNT = 2.0D;
    private static final int MOVEMENT_MINIMUM_PERIOD = 2;
    private static final int SEQUENCE_MINIMUM_PERIOD = 3;
    private static final int REQUIRED_STRUCTURE_CHANGES = 2;
    private static final float MEANINGFUL_TURN_DEGREES = 15.0F;
    private static final double DIRECTION_CHANGE_SIMILARITY = 0.75D;
    private static final double MIN_DIRECTION_DISTANCE = 0.02D;
    private static final double MIN_SEQUENCE_HORIZONTAL_DISTANCE = 0.25D;
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
    private static final long VARIATION_WINDOW_MILLIS = 15_000L;
    private static final int VARIATION_MINIMUM_SAMPLES = 12;
    private static final int VARIATION_DISTINCT_SIGNATURES = 3;
    private static final int VARIATION_MINIMUM_FEATURE_SAMPLES = 4;
    private static final double VARIATION_TEMPORAL_MINIMUM_CV = 0.18D;
    private static final double VARIATION_MOVEMENT_MINIMUM_CV = 0.18D;
    private static final double VARIATION_ROTATION_MINIMUM_CV = 0.20D;

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
        CycleResult structured = sequenceRecurrence(samples, nowMillis);
        CycleResult heartbeat = heartbeatRecurrence(samples, nowMillis);
        CycleResult cadence = actionCadenceRecurrence(samples, nowMillis);
        CycleResult monotony = stationaryActionMonotony(samples, nowMillis);
        return strongerCycle(strongerCycle(structured, heartbeat), strongerCycle(cadence, monotony));
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
        CycleResult dominant = sequence.score() >= movement.score() ? sequence : movement;
        return new Analysis(click.score(), movement.score(), rotation.score(), sequence.score(),
                evidence, Math.max(movement.repetitions(), sequence.repetitions()),
                dominant.cycleMillis(), varied, click.count(), rotation.count());
    }

    private Regularity clickRegularity(List<BehaviorSample> samples, long nowMillis) {
        long cutoff = nowMillis - settings.click().windowMillis();
        List<Long> times = collectClickTimes(samples, cutoff);
        if (times.size() < settings.click().minimumSwings()) {
            return new Regularity(0.0D, times.size());
        }
        double cv = BehaviorSimilarity.coefficientOfVariationOfIntervals(times);
        return new Regularity(BehaviorSimilarity.cvScore(cv, settings.click().maxCv()), times.size());
    }

    private static List<Long> collectClickTimes(List<BehaviorSample> samples, long cutoff) {
        List<Long> times = new ArrayList<>();
        long lastAcceptedAttackMillis = Long.MIN_VALUE;
        for (BehaviorSample sample : samples) {
            if (sample.timestampMillis() < cutoff
                    || (!sample.has(BehaviorSample.SWING) && !sample.has(BehaviorSample.ATTACK))) {
                continue;
            }
            if (isAttackFanout(sample, lastAcceptedAttackMillis)) {
                continue;
            }
            times.add(sample.timestampMillis());
            if (sample.has(BehaviorSample.ATTACK)) {
                lastAcceptedAttackMillis = sample.timestampMillis();
            }
        }
        return times;
    }

    private static boolean isAttackFanout(BehaviorSample sample, long lastAcceptedAttackMillis) {
        if (!sample.has(BehaviorSample.ATTACK) || lastAcceptedAttackMillis == Long.MIN_VALUE) {
            return false;
        }
        long delta = sample.timestampMillis() - lastAcceptedAttackMillis;
        return delta >= 0L && delta <= ACTION_FANOUT_DEDUP_MILLIS;
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
        double interval = BehaviorSimilarity.cvScore(
                BehaviorSimilarity.coefficientOfVariationOfIntervals(times), settings.rotation().maxCv());
        double amount = BehaviorSimilarity.cvScore(
                BehaviorSimilarity.coefficientOfVariation(amounts), settings.rotation().maxCv());
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

    private CycleResult actionCadenceRecurrence(List<BehaviorSample> samples, long nowMillis) {
        Map<Integer, List<Long>> timesBySignature = collectActionTimes(samples,
                nowMillis - CADENCE_WINDOW_MILLIS);
        CycleResult best = CycleResult.NONE;
        for (List<Long> times : timesBySignature.values()) {
            best = strongerCycle(best, cadenceCandidate(times));
        }
        return best;
    }

    private CycleResult cadenceCandidate(List<Long> times) {
        if (times.size() < CADENCE_MIN_SAMPLES) {
            return CycleResult.NONE;
        }
        long span = times.get(times.size() - 1) - times.get(0);
        if (span < CADENCE_MIN_SPAN_MILLIS) {
            return CycleResult.NONE;
        }
        double cv = BehaviorSimilarity.coefficientOfVariationOfIntervals(times);
        if (!Double.isFinite(cv) || cv > CADENCE_MAX_CV) {
            return CycleResult.NONE;
        }
        double normalized = 1.0D - cv / CADENCE_MAX_CV;
        double score = CADENCE_BASE_SCORE + CADENCE_SCORE_RANGE * normalized;
        return new CycleResult(BehaviorSimilarity.clamp01(score), times.size(),
                Math.round(BehaviorSimilarity.meanInterval(times)), 1);
    }

    private CycleResult stationaryActionMonotony(List<BehaviorSample> samples, long nowMillis) {
        long cutoff = nowMillis - MONOTONY_WINDOW_MILLIS;
        Map<Integer, Integer> signatureCounts = new HashMap<>();
        Map<Integer, Long> lastAcceptedBySignature = new HashMap<>();
        List<Long> acceptedTimes = new ArrayList<>();
        boolean independentVariation = false;
        for (BehaviorSample sample : samples) {
            if (!sample.patternEligible() || sample.timestampMillis() < cutoff) {
                continue;
            }
            int actionSignature = cadenceActionSignature(sample);
            if (actionSignature == 0) {
                independentVariation |= hasIndependentPhysicalVariation(sample);
                continue;
            }
            if (isDuplicateActionFanout(actionSignature, sample.timestampMillis(),
                    lastAcceptedBySignature)) {
                continue;
            }
            lastAcceptedBySignature.put(actionSignature, sample.timestampMillis());
            signatureCounts.merge(actionSignature, 1, Integer::sum);
            acceptedTimes.add(sample.timestampMillis());
        }
        if (independentVariation || acceptedTimes.size() < MONOTONY_MIN_SAMPLES) {
            return CycleResult.NONE;
        }
        long span = acceptedTimes.get(acceptedTimes.size() - 1) - acceptedTimes.get(0);
        if (span < MONOTONY_MIN_SPAN_MILLIS) {
            return CycleResult.NONE;
        }
        int dominant = 0;
        for (int count : signatureCounts.values()) {
            dominant = Math.max(dominant, count);
        }
        if ((double) dominant / acceptedTimes.size() < MONOTONY_DOMINANT_RATIO) {
            return CycleResult.NONE;
        }
        return new CycleResult(MONOTONY_SCORE, acceptedTimes.size(),
                Math.round(BehaviorSimilarity.meanInterval(acceptedTimes)), 1);
    }

    private static boolean hasIndependentPhysicalVariation(BehaviorSample sample) {
        return (sample.hasMovement() && sample.horizontalDistance() >= MONOTONY_MOVEMENT_DISTANCE)
                || (sample.hasRotation() && sample.turnAmount() >= MONOTONY_ROTATION_DEGREES);
    }

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
        Map<Integer, List<Long>> timesBySignature = new HashMap<>();
        Map<Integer, Long> lastAcceptedBySignature = new HashMap<>();
        for (BehaviorSample sample : samples) {
            if (!heartbeatSampleEligible(sample, cutoff)) {
                continue;
            }
            int actionSignature = cadenceActionSignature(sample);
            if (actionSignature != 0 && isDuplicateActionFanout(actionSignature,
                    sample.timestampMillis(), lastAcceptedBySignature)) {
                continue;
            }
            int signature = BehaviorSimilarity.normalizedActionSignature(sample);
            appendTime(timesBySignature, signature, sample.timestampMillis());
            if (actionSignature != 0) {
                lastAcceptedBySignature.put(actionSignature, sample.timestampMillis());
            }
        }
        return timesBySignature;
    }

    private Map<Integer, List<Long>> collectActionTimes(List<BehaviorSample> samples, long cutoff) {
        Map<Integer, List<Long>> timesBySignature = new HashMap<>();
        Map<Integer, Long> lastAcceptedBySignature = new HashMap<>();
        for (BehaviorSample sample : samples) {
            if (!sample.patternEligible() || sample.timestampMillis() < cutoff) {
                continue;
            }
            int signature = cadenceActionSignature(sample);
            if (signature == 0 || isDuplicateActionFanout(signature,
                    sample.timestampMillis(), lastAcceptedBySignature)) {
                continue;
            }
            appendTime(timesBySignature, signature, sample.timestampMillis());
            lastAcceptedBySignature.put(signature, sample.timestampMillis());
        }
        return timesBySignature;
    }

    private static int cadenceActionSignature(BehaviorSample sample) {
        return sample.actions() & CADENCE_ACTION_MASK;
    }

    private static boolean isDuplicateActionFanout(int signature,
                                                    long timestampMillis,
                                                    Map<Integer, Long> lastAcceptedBySignature) {
        Long previous = lastAcceptedBySignature.get(signature);
        if (previous == null) {
            return false;
        }
        long delta = timestampMillis - previous;
        return delta >= 0L && delta <= ACTION_FANOUT_DEDUP_MILLIS;
    }

    private boolean heartbeatSampleEligible(BehaviorSample sample, long cutoff) {
        return sample.patternEligible()
                && sample.timestampMillis() >= cutoff
                && heartbeatEligible(sample)
                && BehaviorSimilarity.normalizedActionSignature(sample) != 0;
    }

    private static void appendTime(Map<Integer, List<Long>> timesBySignature,
                                   int signature,
                                   long timestampMillis) {
        timesBySignature.computeIfAbsent(signature, ignored -> new ArrayList<>()).add(timestampMillis);
    }

    private CycleResult heartbeatCandidate(List<Long> times) {
        if (times.size() < HEARTBEAT_MIN_SAMPLES) {
            return CycleResult.NONE;
        }
        long span = times.get(times.size() - 1) - times.get(0);
        double mean = BehaviorSimilarity.meanInterval(times);
        if (span < HEARTBEAT_MIN_SPAN_MILLIS || mean < HEARTBEAT_MIN_INTERVAL_MILLIS) {
            return CycleResult.NONE;
        }
        double cv = BehaviorSimilarity.coefficientOfVariationOfIntervals(times);
        if (!Double.isFinite(cv) || cv > HEARTBEAT_MAX_CV) {
            return CycleResult.NONE;
        }
        double score = HEARTBEAT_BASE_SCORE
                + HEARTBEAT_SCORE_RANGE * (1.0D - cv / HEARTBEAT_MAX_CV);
        return new CycleResult(BehaviorSimilarity.clamp01(score), times.size(), Math.round(mean), 1);
    }

    private static boolean heartbeatEligible(BehaviorSample sample) {
        return sample.has(BehaviorSample.COMMAND)
                || sample.has(BehaviorSample.CHAT)
                || sample.has(BehaviorSample.SWING)
                || sample.has(BehaviorSample.ATTACK)
                || sample.has(BehaviorSample.INTERACT)
                || sample.has(BehaviorSample.BLOCK_BREAK)
                || sample.has(BehaviorSample.BLOCK_PLACE)
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
            double similarity = BehaviorSimilarity.cycleSimilarity(
                    samples, candidateStart, referenceStart, period, movementOnly);
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
        return movementOnly ? hasMovementStructure(structure) : hasSequenceStructure(structure);
    }

    private CycleStructure summarizeCycleStructure(List<BehaviorSample> samples, int start, int period) {
        Set<Integer> distinctSignatures = new HashSet<>();
        int directionChanges = 0;
        int meaningfulTurns = 0;
        double horizontalTotal = 0.0D;
        for (int index = 0; index < period; index++) {
            BehaviorSample current = samples.get(start + index);
            int semantic = BehaviorSimilarity.normalizedActionSignature(current);
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
        return BehaviorSimilarity.directionSimilarity(current, next) < DIRECTION_CHANGE_SIMILARITY
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
        return BehaviorSimilarity.clamp01(evidence);
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
        double adjusted = evidence;
        if (clickOnlyRecently && click.score() >= STRONG_SIGNAL_THRESHOLD) {
            adjusted = Math.max(adjusted, CLICK_ONLY_FLOOR);
        }
        if (click.score() >= EXTREME_REGULARITY_THRESHOLD
                && click.count() >= settings.click().minimumSwings() * MULTI_SIGNAL_COUNT) {
            adjusted = Math.max(adjusted, CLICK_FLOOR);
        }
        return adjusted;
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
        List<Long> times = new ArrayList<>();
        Set<Integer> distinctSignatures = new HashSet<>();
        List<Double> movementDistances = new ArrayList<>();
        List<Double> rotationAmounts = new ArrayList<>();
        for (BehaviorSample sample : samples) {
            if (!sample.patternEligible() || sample.timestampMillis() < cutoff) {
                continue;
            }
            times.add(sample.timestampMillis());
            int signature = BehaviorSimilarity.normalizedActionSignature(sample);
            if (signature != 0) {
                distinctSignatures.add(signature);
            }
            if (sample.hasMovement()) {
                movementDistances.add(sample.distance());
            }
            if (sample.hasRotation() && sample.turnAmount() >= MIN_ROTATION_AMOUNT) {
                rotationAmounts.add((double) sample.turnAmount());
            }
        }
        if (times.size() < VARIATION_MINIMUM_SAMPLES) {
            return false;
        }
        boolean signatureDiversity = distinctSignatures.size() >= VARIATION_DISTINCT_SIGNATURES;
        boolean temporalVariation = BehaviorSimilarity.coefficientOfVariationOfIntervals(times)
                >= VARIATION_TEMPORAL_MINIMUM_CV;
        boolean movementVariation = movementDistances.size() >= VARIATION_MINIMUM_FEATURE_SAMPLES
                && BehaviorSimilarity.coefficientOfVariation(movementDistances)
                >= VARIATION_MOVEMENT_MINIMUM_CV;
        boolean rotationVariation = rotationAmounts.size() >= VARIATION_MINIMUM_FEATURE_SAMPLES
                && BehaviorSimilarity.coefficientOfVariation(rotationAmounts)
                >= VARIATION_ROTATION_MINIMUM_CV;
        return (signatureDiversity && temporalVariation)
                || (movementVariation && (signatureDiversity || rotationVariation))
                || (rotationVariation && temporalVariation);
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
