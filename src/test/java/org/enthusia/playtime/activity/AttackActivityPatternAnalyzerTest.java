package org.enthusia.playtime.activity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AttackActivityPatternAnalyzerTest {
    private static final double SUSPICIOUS_THRESHOLD = 0.86D;
    private static final AdvancedDetectionSettings SETTINGS = new AdvancedDetectionSettings(
            new AdvancedDetectionSettings.Click(true, 20_000L, 25, 0.08D),
            new AdvancedDetectionSettings.Rotation(true, 30_000L, 10, 0.06D),
            new AdvancedDetectionSettings.Movement(true, 45_000L, 24, 4, 0.96D, 32, 900L),
            new AdvancedDetectionSettings.Sequence(true, 60_000L, 12, 3, 0.95D, 40),
            new AdvancedDetectionSettings.Scoring(SUSPICIOUS_THRESHOLD, 0.30D, 0.025D,
                    1000L, 256, 15_000L, 300_000L, 512));
    private final ActivityPatternAnalyzer analyzer = new ActivityPatternAnalyzer(SETTINGS);

    @Test
    void fixedRateAttackOnlyInputIsStrongEvidence() {
        List<BehaviorSample> samples = new ArrayList<>();
        long time = 1_000L;
        for (int i = 0; i < 70; i++) {
            samples.add(attack(time));
            time += 200L;
        }

        ActivityPatternAnalyzer.Analysis analysis = analyzer.analyze(samples, time, true);

        assertTrue(analysis.clickRegularity() >= 0.985D);
        assertTrue(analysis.combinedEvidence() >= SUSPICIOUS_THRESHOLD);
    }

    @Test
    void sweepDamageFanoutCannotHideFixedRateAttacks() {
        List<BehaviorSample> samples = new ArrayList<>();
        long time = 1_000L;
        for (int i = 0; i < 70; i++) {
            samples.add(attack(time));
            samples.add(attack(time + 3L));
            samples.add(attack(time + 7L));
            samples.add(attack(time + 11L));
            time += 200L;
        }

        ActivityPatternAnalyzer.Analysis analysis = analyzer.analyze(samples, time, true);

        assertTrue(analysis.clickRegularity() >= 0.985D);
        assertTrue(analysis.combinedEvidence() >= SUSPICIOUS_THRESHOLD);
    }

    @Test
    void slowAttackHeartbeatIsStrongEvidence() {
        List<BehaviorSample> samples = new ArrayList<>();
        long time = 1_000L;
        for (int i = 0; i < 5; i++) {
            samples.add(attack(time));
            time += 30_000L;
        }
        long analyzeAt = time - 30_000L;

        ActivityPatternAnalyzer.Analysis analysis = analyzer.analyze(samples, analyzeAt, true);

        assertTrue(analysis.sequenceRepetition() >= 0.98D);
        assertTrue(analysis.combinedEvidence() >= SUSPICIOUS_THRESHOLD);
    }

    @Test
    void irregularStationaryAttackTimingIsConservativelySuspicious() {
        List<BehaviorSample> samples = new ArrayList<>();
        int[] intervals = {93, 417, 181, 764, 128, 342, 599, 151, 486, 237, 905, 116};
        long time = 1_000L;
        for (int i = 0; i < 70; i++) {
            samples.add(attack(time));
            time += intervals[i % intervals.length];
        }

        ActivityPatternAnalyzer.Analysis analysis = analyzer.analyze(samples, time, true);

        assertTrue(analysis.combinedEvidence() >= SUSPICIOUS_THRESHOLD);
    }

    @Test
    void irregularManualAttackTimingWithPhysicalVariationIsNotSuspicious() {
        List<BehaviorSample> samples = new ArrayList<>();
        int[] intervals = {93, 417, 181, 764, 128, 342, 599, 151, 486, 237, 905, 116};
        Random random = new Random(0xA77ACCL);
        long time = 1_000L;
        for (int i = 0; i < 70; i++) {
            samples.add(attack(time));
            time += intervals[i % intervals.length];
            if (random.nextInt(3) != 0) {
                double dx = 0.18D + random.nextDouble() * 0.35D;
                double dz = (random.nextDouble() - 0.5D) * 0.45D;
                float yaw = (float) ((random.nextDouble() - 0.5D) * 90.0D);
                int flags = BehaviorSample.MOVE;
                if (Math.abs(yaw) >= 2.0F) {
                    flags |= BehaviorSample.ROTATE;
                }
                samples.add(new BehaviorSample(time, flags,
                        dx, 0.0D, dz, yaw, 0.0F, true));
                time += 40L + random.nextInt(180);
            }
        }

        ActivityPatternAnalyzer.Analysis analysis = analyzer.analyze(samples, time, true);

        assertTrue(analysis.combinedEvidence() < SUSPICIOUS_THRESHOLD,
                () -> "unexpected suspicious evidence: " + analysis);
    }

    private static BehaviorSample attack(long time) {
        return new BehaviorSample(time, BehaviorSample.ATTACK,
                0.0D, 0.0D, 0.0D, 0.0F, 0.0F, true);
    }
}
