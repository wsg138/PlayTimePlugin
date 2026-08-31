package org.enthusia.playtime.service;

import org.enthusia.playtime.activity.ActivityState;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SuspiciousAllowancePersistenceTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void idleAndAfkMinutesCannotClearSuspiciousHistory() {
        UUID uuid = UUID.randomUUID();
        PlaytimeAccrualTracker tracker = new PlaytimeAccrualTracker(2, Map.of());
        tracker.connect(uuid, 0L, 1L);

        var first = tracker.sample(uuid, 60L * SECOND, ActivityState.SUSPICIOUS, 1L);
        assertEquals(0, first.activeMinutes());
        assertEquals(1, first.afkMinutes());
        var blocked = tracker.sample(uuid, 120L * SECOND, ActivityState.SUSPICIOUS, 1L);
        assertEquals(0, blocked.activeMinutes());
        assertEquals(1, blocked.afkMinutes());

        var idle = tracker.sample(uuid, 180L * SECOND, ActivityState.IDLE, 1L);
        assertEquals(1, idle.afkMinutes());
        assertEquals(2, idle.suspiciousStreak());

        var afk = tracker.sample(uuid, 240L * SECOND, ActivityState.AFK, 1L);
        assertEquals(1, afk.afkMinutes());
        assertEquals(2, afk.suspiciousStreak());

        var resumedAutomation = tracker.sample(
                uuid, 300L * SECOND, ActivityState.SUSPICIOUS, 1L);
        assertEquals(0, resumedAutomation.activeMinutes());
        assertEquals(1, resumedAutomation.afkMinutes());
        assertEquals(3, resumedAutomation.suspiciousStreak());
    }

    @Test
    void ordinaryActiveStateCannotClearSuspiciousHistory() {
        UUID uuid = UUID.randomUUID();
        PlaytimeAccrualTracker tracker = new PlaytimeAccrualTracker(1, Map.of());
        tracker.connect(uuid, 0L, 1L);

        var suspicious = tracker.sample(uuid, 60L * SECOND, ActivityState.SUSPICIOUS, 1L);
        assertEquals(0, suspicious.activeMinutes());
        assertEquals(1, suspicious.afkMinutes());
        var active = tracker.sample(uuid, 120L * SECOND, ActivityState.ACTIVE, 1L);
        assertEquals(1, active.activeMinutes());
        assertEquals(1, active.suspiciousStreak());

        var resumedAutomation = tracker.sample(
                uuid, 180L * SECOND, ActivityState.SUSPICIOUS, 1L);
        assertEquals(0, resumedAutomation.activeMinutes());
        assertEquals(1, resumedAutomation.afkMinutes());
        assertEquals(2, resumedAutomation.suspiciousStreak());
    }

    @Test
    void detectorApprovedResetMarkerClearsHistoryButDoesNotGrantSuspiciousCredit() {
        UUID uuid = UUID.randomUUID();
        PlaytimeAccrualTracker tracker = new PlaytimeAccrualTracker(1, Map.of());
        tracker.connect(uuid, 0L, 1L);
        tracker.sample(uuid, 60L * SECOND, ActivityState.SUSPICIOUS, 1L);

        tracker.sample(uuid, 61L * SECOND, ActivityState.ACTIVE, 2L);
        var resetAutomation = tracker.sample(
                uuid, 121L * SECOND, ActivityState.SUSPICIOUS, 2L);

        assertEquals(0, resetAutomation.activeMinutes());
        assertEquals(1, resetAutomation.afkMinutes());
        assertEquals(1, resetAutomation.suspiciousStreak());
    }

    @Test
    void detectorApprovedResetDropsPartialSuspiciousTailBeforeCleanAccrual() {
        UUID uuid = UUID.randomUUID();
        PlaytimeAccrualTracker tracker = new PlaytimeAccrualTracker(1, Map.of());
        tracker.connect(uuid, 0L, 1L);

        var partialSuspicion = tracker.sample(
                uuid, 30L * SECOND, ActivityState.SUSPICIOUS, 1L);
        assertEquals(0, partialSuspicion.completedMinutes());
        assertEquals(0, partialSuspicion.suspiciousStreak());

        var reset = tracker.sample(uuid, 31L * SECOND, ActivityState.ACTIVE, 2L);
        assertEquals(0, reset.completedMinutes());
        assertEquals(0, reset.suspiciousStreak());

        var cleanMinute = tracker.sample(uuid, 90L * SECOND, ActivityState.ACTIVE, 2L);
        assertEquals(1, cleanMinute.activeMinutes());
        assertEquals(0, cleanMinute.afkMinutes());
        assertEquals(0, cleanMinute.suspiciousStreak());
    }

    @Test
    void resetMarkerThatAdvancedWhileOfflineClearsReconnectGuard() {
        UUID uuid = UUID.randomUUID();
        PlaytimeAccrualTracker tracker = new PlaytimeAccrualTracker(1, Map.of());
        tracker.connect(uuid, 0L, 1L);
        tracker.sample(uuid, 60L * SECOND, ActivityState.SUSPICIOUS, 1L);
        tracker.disconnect(uuid, 61L * SECOND);

        tracker.connect(uuid, 100L * SECOND, 2L);
        var active = tracker.sample(uuid, 160L * SECOND, ActivityState.ACTIVE, 2L);

        assertEquals(ActivityState.ACTIVE, active.state());
        assertEquals(1, active.activeMinutes());
        assertEquals(0, active.suspiciousStreak());
        assertFalse(tracker.snapshot().get(uuid).reconnectGuard());
    }
}
