package org.enthusia.playtime.activity;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongHorizonActivityAnalyzerTest {
    private static final long IDLE_MILLIS = 60_000L;

    @Test
    void randomizedSlowKeepaliveCannotFarmActiveForever() {
        Deque<Long> pulses = new ArrayDeque<>();
        long[] times = {1_000L, 46_000L, 99_000L, 143_000L, 194_000L};
        for (long time : times) {
            LongHorizonActivityAnalyzer.recordPulse(pulses, time);
        }

        double evidence = LongHorizonActivityAnalyzer.sparseKeepaliveEvidence(
                pulses, 194_500L, IDLE_MILLIS);

        assertTrue(evidence >= 0.99D);
    }

    @Test
    void ordinaryTwoSecondInputCadenceIsNotSparseKeepaliveEvidence() {
        Deque<Long> pulses = new ArrayDeque<>();
        for (long time = 1_000L; time <= 121_000L; time += 2_000L) {
            LongHorizonActivityAnalyzer.recordPulse(pulses, time);
        }

        assertEquals(0.0D, LongHorizonActivityAnalyzer.sparseKeepaliveEvidence(
                pulses, 121_500L, IDLE_MILLIS));
    }

    @Test
    void longHorizonVerySparseHumanizedKeepaliveStillBecomesSuspicious() {
        Deque<Long> pulses = new ArrayDeque<>();
        long time = 1_000L;
        int[] gaps = {11_000, 14_000, 9_000, 16_000, 12_000, 13_000};
        int index = 0;
        while (time <= 241_000L) {
            LongHorizonActivityAnalyzer.recordPulse(pulses, time);
            time += gaps[index++ % gaps.length];
        }

        double evidence = LongHorizonActivityAnalyzer.sparseKeepaliveEvidence(
                pulses, 241_500L, IDLE_MILLIS);

        assertTrue(evidence >= 0.90D);
    }

    @Test
    void sustainedPerSecondActivityIsNotSparseKeepaliveEvidence() {
        Deque<Long> pulses = new ArrayDeque<>();
        for (long time = 1_000L; time <= 121_000L; time += 1_000L) {
            LongHorizonActivityAnalyzer.recordPulse(pulses, time);
        }

        assertEquals(0.0D, LongHorizonActivityAnalyzer.sparseKeepaliveEvidence(
                pulses, 121_500L, IDLE_MILLIS));
        assertTrue(LongHorizonActivityAnalyzer.hasDenseRecentActivity(pulses, 121_500L));
    }

    @Test
    void highRateRawSpamCannotEvictMinutesOfPulseHistory() {
        Deque<Long> pulses = new ArrayDeque<>();
        for (long second = 1_000L; second <= 181_000L; second += 1_000L) {
            for (int burst = 0; burst < 500; burst++) {
                LongHorizonActivityAnalyzer.recordPulse(pulses, second + burst);
            }
        }

        assertEquals(181, pulses.size());
        assertTrue(LongHorizonActivityAnalyzer.hasDenseRecentActivity(pulses, 181_999L));
    }

    @Test
    void sparseHumanizerBurstCannotQualifyAsRecoveryActivity() {
        Deque<Long> pulses = new ArrayDeque<>();
        long[] times = {1_000L, 8_000L, 13_000L, 21_000L, 29_000L};
        for (long time : times) {
            LongHorizonActivityAnalyzer.recordPulse(pulses, time);
        }

        assertFalse(LongHorizonActivityAnalyzer.hasDenseRecentActivity(pulses, 30_000L));
    }
}
