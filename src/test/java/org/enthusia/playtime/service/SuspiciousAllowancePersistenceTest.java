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
    void idleAndAfkMinutesCannotReplenishSuspiciousAllowance() {
        UUID uuid = UUID.randomUUID();
        PlaytimeAccrualTracker tracker = new PlaytimeAccrualTracker(2, Map.of());
        tracker.connect(uuid, 0L, 1L);

        assertEquals(1, tracker.sample(
                uuid, 60L * SECOND, ActivityState.SUSPICIOUS, 1L).activeMinutes());
        var blocked = tracker.sample(
                uuid, 120L * SECOND, ActivityState.SUSPICIOUS, 1L);
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
    void ordinaryActiveStateCannotReplenishSuspiciousAllowance() {
        UUID uuid = UUID.randomUUID();
        PlaytimeAccrualTracker tracker = new PlaytimeAccrualTracker(1, Map.of());
        tracker.connect(uuid, 0L, 1L);

        assertEquals(1, tracker.sample(
                uuid, 60L * SECOND, ActivityState.SUSPICIOUS, 1L).activeMinutes());
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
    void detectorApprovedResetMarkerReplenishesAllowance() {
        UUID uuid = UUID.randomUUID();
        PlaytimeAccrualTracker tracker = new PlaytimeAccrualTracker(1, Map.of());
        tracker.connect(uuid, 0L, 1L);
        tracker.sample(uuid, 60L * SECOND, ActivityState.SUSPICIOUS, 1L);

        tracker.sample(uuid, 61L * SECOND, ActivityState.ACTIVE, 2L);
        var resetAutomation = tracker.sample(
                uuid, 121L * SECOND, ActivityState.SUSPICIOUS, 2L);

        assertEquals(1, resetAutomation.activeMinutes());
        assertEquals(1, resetAutomation.suspiciousStreak());
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
