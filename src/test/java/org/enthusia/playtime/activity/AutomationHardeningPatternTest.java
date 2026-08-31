package org.enthusia.playtime.activity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationHardeningPatternTest {
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
    void regularInteractOnlyAutomationIsSuspicious() {
        List<BehaviorSample> samples = new ArrayList<>();
        long time = 1_000L;
        for (int i = 0; i < 60; i++) {
            samples.add(action(time, BehaviorSample.INTERACT));
            time += 250L;
        }

        ActivityPatternAnalyzer.Analysis analysis = analyzer.analyze(samples, time, false);

        assertTrue(analysis.sequenceRepetition() >= 0.975D);
        assertTrue(analysis.combinedEvidence() >= SUSPICIOUS_THRESHOLD);
    }

    @Test
    void randomizedStationarySingleActionStillBecomesSuspicious() {
        List<BehaviorSample> samples = new ArrayList<>();
        int[] intervals = {310, 690, 420, 830, 270, 540, 760, 380, 920, 460, 610, 350};
        long time = 1_000L;
        for (int i = 0; i < 55; i++) {
            samples.add(action(time, BehaviorSample.INTERACT | BehaviorSample.BLOCK_PLACE));
            time += intervals[i % intervals.length];
        }

        ActivityPatternAnalyzer.Analysis analysis = analyzer.analyze(samples, time, false);

        assertTrue(analysis.sequenceRepetition() >= 0.985D);
        assertTrue(analysis.combinedEvidence() >= SUSPICIOUS_THRESHOLD);
    }

    @Test
    void regularStationaryJumpAutomationIsSuspicious() {
        List<BehaviorSample> samples = new ArrayList<>();
        long time = 1_000L;
        for (int i = 0; i < 45; i++) {
            samples.add(new BehaviorSample(time, BehaviorSample.MOVE | BehaviorSample.JUMP,
                    0.0D, 0.34D, 0.0D, 0.0F, 0.0F, true));
            time += 650L;
        }

        ActivityPatternAnalyzer.Analysis analysis = analyzer.analyze(samples, time, false);

        assertTrue(analysis.sequenceRepetition() >= 0.975D);
        assertTrue(analysis.combinedEvidence() >= SUSPICIOUS_THRESHOLD);
    }

    @Test
    void twoActionMacroLikeNoiseIsNotConvincingRecoveryVariation() {
        List<BehaviorSample> samples = new ArrayList<>();
        int[] intervals = {173, 911, 284, 637, 359, 1187, 446, 721, 205, 1013, 578, 329, 864, 492};
        int[] actions = {
                BehaviorSample.INTERACT, BehaviorSample.COMMAND, BehaviorSample.INTERACT,
                BehaviorSample.INTERACT, BehaviorSample.COMMAND, BehaviorSample.INTERACT,
                BehaviorSample.COMMAND, BehaviorSample.COMMAND, BehaviorSample.INTERACT,
                BehaviorSample.COMMAND, BehaviorSample.INTERACT, BehaviorSample.COMMAND,
                BehaviorSample.COMMAND, BehaviorSample.INTERACT
        };
        long time = 1_000L;
        for (int i = 0; i < actions.length; i++) {
            samples.add(action(time, actions[i]));
            time += intervals[i];
        }

        ActivityPatternAnalyzer.Analysis analysis = analyzer.analyze(samples, time, false);

        assertFalse(analysis.convincingVariation());
    }

    @Test
    void genuinelyVariedPhysicalActivityCanStillRecoverTrust() {
        List<BehaviorSample> samples = new ArrayList<>();
        Random random = new Random(0xA11CE55L);
        long time = 1_000L;
        int[] semantic = {BehaviorSample.INTERACT, BehaviorSample.SWING, BehaviorSample.BLOCK_BREAK};
        for (int i = 0; i < 24; i++) {
            if (i % 4 == 0) {
                samples.add(action(time, semantic[(i / 4) % semantic.length]));
            } else {
                double dx = 0.08D + random.nextDouble() * 0.45D;
                double dz = (random.nextDouble() - 0.5D) * 0.55D;
                float yaw = (float) ((random.nextDouble() - 0.5D) * 95.0D);
                int flags = BehaviorSample.MOVE;
                if (Math.abs(yaw) > 2.0F) {
                    flags |= BehaviorSample.ROTATE;
                }
                samples.add(new BehaviorSample(time, flags,
                        dx, 0.0D, dz, yaw, 0.0F, true));
            }
            time += 140L + random.nextInt(930);
        }

        ActivityPatternAnalyzer.Analysis analysis = analyzer.analyze(samples, time, false);

        assertTrue(analysis.combinedEvidence() < 0.30D);
        assertTrue(analysis.convincingVariation());
    }

    private static BehaviorSample action(long time, int actions) {
        return new BehaviorSample(time, actions,
                0.0D, 0.0D, 0.0D, 0.0F, 0.0F, true);
    }
}
