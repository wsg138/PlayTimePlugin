package org.enthusia.playtime.activity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityPatternAnalyzerTest {
    private static final float TEST_ROTATION_THRESHOLD = 2.0F;
    private static final AdvancedDetectionSettings SETTINGS = new AdvancedDetectionSettings(
            new AdvancedDetectionSettings.Click(true, 20_000L, 25, 0.08D),
            new AdvancedDetectionSettings.Rotation(true, 30_000L, 10, 0.06D),
            new AdvancedDetectionSettings.Movement(true, 45_000L, 24, 4, 0.96D, 32, 900L),
            new AdvancedDetectionSettings.Sequence(true, 60_000L, 12, 3, 0.95D, 40),
            new AdvancedDetectionSettings.Scoring(0.86D, 0.30D, 0.025D,
                    1000L, 256, 15_000L, 300_000L, 512));
    private final ActivityPatternAnalyzer analyzer = new ActivityPatternAnalyzer(SETTINGS);

    @Test
    void fixedRateAutoclickerIsStrongEvidence() {
        List<BehaviorSample> samples = new ArrayList<>();
        long time = 1_000L;
        for (int i = 0; i < 70; i++) {
            samples.add(action(time, BehaviorSample.SWING));
            time += 200L;
        }
        assertSuspicious(samples, time, true);
    }

    @Test
    void smallTimingJitterAutoclickerIsStrongEvidence() {
        List<BehaviorSample> samples = new ArrayList<>();
        int[] jitter = {-6, 4, -3, 7, -5, 2};
        long time = 1_000L;
        for (int i = 0; i < 70; i++) {
            samples.add(action(time, BehaviorSample.SWING));
            time += 205L + jitter[i % jitter.length];
        }
        assertSuspicious(samples, time, true);
    }

    @Test
    void identicalHeadRotationIsStrongEvidence() {
        List<BehaviorSample> samples = new ArrayList<>();
        long time = 1_000L;
        for (int i = 0; i < 24; i++) {
            samples.add(new BehaviorSample(time, BehaviorSample.ROTATE,
                    0.0D, 0.0D, 0.0D, 45.0F, 0.0F, true));
            time += 300L;
        }
        assertSuspicious(samples, time, false);
    }

    @Test
    void forwardBackLoopIsStrongEvidence() {
        assertSuspicious(repeatingTwoStepMovement(14, true, false), 20_000L, false);
    }

    @Test
    void strafeLoopIsStrongEvidence() {
        assertSuspicious(repeatingTwoStepMovement(14, false, false), 20_000L, false);
    }

    @Test
    void jumpForwardBackLoopIsStrongEvidence() {
        assertSuspicious(repeatingTwoStepMovement(14, true, true), 20_000L, false);
    }

    @Test
    void exactSquarePathIsStrongEvidence() {
        List<BehaviorSample> samples = new ArrayList<>();
        long time = 1_000L;
        double[][] vectors = {{0.28D, 0.0D}, {0.0D, 0.28D}, {-0.28D, 0.0D}, {0.0D, -0.28D}};
        for (int cycle = 0; cycle < 8; cycle++) {
            for (double[] vector : vectors) {
                samples.add(move(time, vector[0], 0.0D, vector[1], 90.0F, 0));
                time += 500L;
            }
        }
        assertSuspicious(samples, time, false);
    }

    @Test
    void repeatedNinetyDegreeTurnLoopIsStrongEvidence() {
        List<BehaviorSample> samples = new ArrayList<>();
        long time = 1_000L;
        for (int i = 0; i < 24; i++) {
            samples.add(new BehaviorSample(time, BehaviorSample.ROTATE,
                    0.0D, 0.0D, 0.0D, 90.0F, 0.0F, true));
            time += 350L;
        }
        assertSuspicious(samples, time, false);
    }

    @Test
    void movementAndClickCycleIsStrongEvidence() {
        assertSuspicious(repeatedCombinedCycle(8, BehaviorSample.SWING), 30_000L, false);
    }

    @Test
    void movementAndAttackCycleIsStrongEvidence() {
        assertSuspicious(repeatedCombinedCycle(8, BehaviorSample.SWING | BehaviorSample.ATTACK),
                30_000L, false);
    }

    @Test
    void movementAndRotationCycleIsStrongEvidence() {
        List<BehaviorSample> samples = new ArrayList<>();
        long time = 1_000L;
        for (int cycle = 0; cycle < 8; cycle++) {
            samples.add(move(time, 0.30D, 0.0D, 0.0D, 0.0F, 0));
            time += 400L;
            samples.add(move(time, 0.0D, 0.0D, 0.30D, 90.0F, BehaviorSample.ROTATE));
            time += 400L;
            samples.add(move(time, -0.30D, 0.0D, 0.0D, 0.0F, 0));
            time += 400L;
            samples.add(move(time, 0.0D, 0.0D, -0.30D, 90.0F, BehaviorSample.ROTATE));
            time += 400L;
        }
        assertSuspicious(samples, time, false);
    }

    @Test
    void movementJumpAndClickCycleIsStrongEvidence() {
        assertSuspicious(repeatedCombinedCycle(8, BehaviorSample.SWING | BehaviorSample.JUMP),
                30_000L, false);
    }

    @Test
    void movementAndBlockPlacementCycleIsStrongEvidence() {
        assertSuspicious(repeatedCombinedCycle(8, BehaviorSample.INTERACT | BehaviorSample.BLOCK_PLACE),
                30_000L, false);
    }

    @Test
    void longerMultiActionCycleIsStrongEvidence() {
        List<BehaviorSample> samples = new ArrayList<>();
        long time = 1_000L;
        for (int cycle = 0; cycle < 7; cycle++) {
            samples.add(move(time, 0.30D, 0.0D, 0.0D, 0.0F, 0));
            time += 350L;
            samples.add(move(time, 0.30D, 0.0D, 0.0D, 0.0F, 0));
            time += 350L;
            samples.add(new BehaviorSample(time, BehaviorSample.ROTATE,
                    0.0D, 0.0D, 0.0D, 90.0F, 0.0F, true));
            time += 250L;
            samples.add(action(time, BehaviorSample.SWING | BehaviorSample.ATTACK));
            time += 210L;
            samples.add(action(time, BehaviorSample.SWING | BehaviorSample.INTERACT | BehaviorSample.BLOCK_PLACE));
            time += 260L;
            samples.add(move(time, -0.30D, 0.22D, 0.0D, 0.0F, BehaviorSample.JUMP));
            time += 430L;
        }
        assertSuspicious(samples, time, false);
    }

    @Test
    void repeatedCycleToleratesTickAndCoordinateJitter() {
        List<BehaviorSample> samples = new ArrayList<>();
        int[] timeJitter = {-18, 12, 5, -9, 20, -4};
        double[] positionJitter = {-0.006D, 0.004D, -0.003D, 0.005D};
        long time = 1_000L;
        for (int cycle = 0; cycle < 12; cycle++) {
            double jitter = positionJitter[cycle % positionJitter.length];
            samples.add(move(time, 0.28D + jitter, 0.0D, 0.0D, 0.0F, 0));
            time += 500L + timeJitter[cycle % timeJitter.length];
            samples.add(move(time, -(0.28D + jitter), 0.0D, 0.0D, 0.0F, 0));
            time += 500L - timeJitter[cycle % timeJitter.length];
        }
        assertSuspicious(samples, time, false);
    }

    @Test
    void straightWalkingAndSprintingAreNotCycles() {
        List<BehaviorSample> samples = new ArrayList<>();
        long time = 1_000L;
        for (int i = 0; i < 100; i++) {
            double speed = 0.20D + ((i * 17) % 13) * 0.004D;
            samples.add(move(time, speed, 0.0D, 0.005D * Math.sin(i), 0.0F, 0));
            time += 230L + ((i * 29) % 80);
        }
        assertNotSuspicious(samples, time);
    }

    @Test
    void manualMiningAndBuildingWithHumanTimingAreNotSuspicious() {
        List<BehaviorSample> samples = new ArrayList<>();
        Random random = new Random(0x5EEDBEEFL);
        long time = 1_000L;
        for (int i = 0; i < 80; i++) {
            boolean placing = random.nextInt(5) == 0;
            int action = placing
                    ? BehaviorSample.SWING | BehaviorSample.INTERACT | BehaviorSample.BLOCK_PLACE
                    : BehaviorSample.SWING | BehaviorSample.BLOCK_BREAK;
            samples.add(action(time, action));
            time += 140L + random.nextInt(360);
            if (i % 13 == 0) {
                time += 250L + random.nextInt(350);
            }
        }
        assertNotSuspicious(samples, time);
    }

    @Test
    void cropFarmingAndPvpStrafingWithVariationAreNotSuspicious() {
        List<BehaviorSample> samples = new ArrayList<>();
        long time = 1_000L;
        for (int i = 0; i < 90; i++) {
            double direction = (i / 5) % 2 == 0 ? 1.0D : -1.0D;
            double distance = direction * (0.16D + ((i * 11) % 9) * 0.013D);
            float yaw = (float) (((i * 37) % 41) - 20);
            int extra = i % 7 == 0 ? BehaviorSample.SWING | BehaviorSample.ATTACK : 0;
            samples.add(move(time, 0.03D * Math.sin(i), 0.0D, distance, yaw, extra));
            time += 190L + ((i * 43) % 170);
        }
        assertNotSuspicious(samples, time);
    }

    @Test
    void parkourAndRepeatedTravelJumpsWithVariationAreNotSuspicious() {
        List<BehaviorSample> samples = new ArrayList<>();
        long time = 1_000L;
        for (int i = 0; i < 80; i++) {
            boolean jump = i % (4 + (i / 13) % 3) == 0;
            double dy = jump ? 0.36D + (i % 5) * 0.015D : (i % 3 == 0 ? -0.22D : 0.0D);
            samples.add(move(time, 0.22D + (i % 7) * 0.012D, dy,
                    0.025D * Math.sin(i * 0.7D), (float) ((i * 13) % 11 - 5),
                    jump ? BehaviorSample.JUMP : 0));
            time += 210L + ((i * 31) % 120);
        }
        assertNotSuspicious(samples, time);
    }

    @Test
    void variedLookingAndChattingWhileStationaryAreNotSuspicious() {
        List<BehaviorSample> samples = new ArrayList<>();
        long time = 1_000L;
        float[] turns = {7.0F, -31.0F, 4.0F, 53.0F, -12.0F, 19.0F, -46.0F};
        for (int i = 0; i < 40; i++) {
            if (i % 8 == 0) {
                samples.add(action(time, BehaviorSample.CHAT));
            } else {
                samples.add(new BehaviorSample(time, BehaviorSample.ROTATE,
                        0.0D, 0.0D, 0.0D, turns[i % turns.length],
                        turns[(i + 2) % turns.length] / 4.0F, true));
            }
            time += 170L + ((i * 83) % 520);
        }
        assertNotSuspicious(samples, time);
    }

    @Test
    void environmentalVehicleWaterAndFlightSamplesAreIgnored() {
        List<BehaviorSample> samples = new ArrayList<>();
        long time = 1_000L;
        for (int i = 0; i < 100; i++) {
            samples.add(new BehaviorSample(time, BehaviorSample.MOVE | BehaviorSample.ROTATE,
                    0.35D, 0.0D, 0.0D, 10.0F, 0.0F, false));
            time += 250L;
        }
        assertNotSuspicious(samples, time);
    }

    private List<BehaviorSample> repeatingTwoStepMovement(int cycles, boolean xAxis, boolean jump) {
        List<BehaviorSample> samples = new ArrayList<>();
        long time = 1_000L;
        double upward = jumpDelta(jump, 0.32D);
        int firstExtra = jumpAction(jump);
        for (int cycle = 0; cycle < cycles; cycle++) {
            samples.add(axisMove(time, xAxis, 0.28D, upward, firstExtra));
            time += 500L;
            samples.add(axisMove(time, xAxis, -0.28D, -upward, 0));
            time += 500L;
        }
        return samples;
    }

    private static BehaviorSample axisMove(long time,
                                           boolean xAxis,
                                           double distance,
                                           double dy,
                                           int extraActions) {
        return xAxis
                ? move(time, distance, dy, 0.0D, 0.0F, extraActions)
                : move(time, 0.0D, dy, distance, 0.0F, extraActions);
    }

    private static double jumpDelta(boolean jump, double distance) {
        return jump ? distance : 0.0D;
    }

    private static int jumpAction(boolean jump) {
        return jump ? BehaviorSample.JUMP : 0;
    }

    private List<BehaviorSample> repeatedCombinedCycle(int cycles, int actionFlags) {
        List<BehaviorSample> samples = new ArrayList<>();
        long time = 1_000L;
        for (int cycle = 0; cycle < cycles; cycle++) {
            samples.add(move(time, 0.28D, 0.0D, 0.0D, 0.0F, 0));
            time += 350L;
            samples.add(action(time, actionFlags));
            time += 250L;
            samples.add(move(time, -0.28D, 0.0D, 0.0D, 0.0F, 0));
            time += 400L;
        }
        return samples;
    }

    private static BehaviorSample move(long time, double dx, double dy, double dz,
                                       float yawDelta, int extraActions) {
        int actions = BehaviorSample.MOVE | extraActions;
        if (Math.abs(yawDelta) >= TEST_ROTATION_THRESHOLD) actions |= BehaviorSample.ROTATE;
        return new BehaviorSample(time, actions, dx, dy, dz, yawDelta, 0.0F, true);
    }

    private static BehaviorSample action(long time, int actions) {
        return new BehaviorSample(time, actions, 0.0D, 0.0D, 0.0D,
                0.0F, 0.0F, true);
    }

    private void assertSuspicious(List<BehaviorSample> samples, long nowMillis, boolean clickOnly) {
        ActivityPatternAnalyzer.Analysis analysis = analyzer.analyze(samples, nowMillis, clickOnly);
        assertTrue(analysis.combinedEvidence() >= SETTINGS.scoring().suspiciousThreshold(),
                () -> "expected suspicious evidence, got " + analysis);
    }

    private void assertNotSuspicious(List<BehaviorSample> samples, long nowMillis) {
        ActivityPatternAnalyzer.Analysis analysis = analyzer.analyze(samples, nowMillis, false);
        assertFalse(analysis.combinedEvidence() >= SETTINGS.scoring().suspiciousThreshold(),
                () -> "unexpected suspicious evidence: " + analysis);
    }
}
