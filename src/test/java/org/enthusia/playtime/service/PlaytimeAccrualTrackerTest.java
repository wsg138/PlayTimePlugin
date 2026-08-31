package org.enthusia.playtime.service;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.enthusia.playtime.activity.ActivityState;
import org.enthusia.playtime.activity.ActivityTracker;
import org.enthusia.playtime.activity.SessionManager;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.enthusia.playtime.util.PerformanceCounters;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaytimeAccrualTrackerTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void joiningSecondsBeforeSampleDoesNotGrantFullMinute() {
        UUID uuid = UUID.randomUUID();
        PlaytimeAccrualTracker tracker = new PlaytimeAccrualTracker(10, Map.of());
        tracker.connect(uuid, 0L, 1L);

        var fiveSeconds = tracker.sample(uuid, 5L * SECOND, ActivityState.ACTIVE, 1L);
        assertEquals(0, fiveSeconds.completedMinutes());
        assertEquals(0, fiveSeconds.activeMinutes());

        var completed = tracker.sample(uuid, 60L * SECOND, ActivityState.ACTIVE, 1L);
        assertEquals(1, completed.completedMinutes());
        assertEquals(1, completed.activeMinutes());
    }

    @Test
    void shortSessionsCarryPartialMinuteAcrossReconnect() {
        UUID uuid = UUID.randomUUID();
        PlaytimeAccrualTracker tracker = new PlaytimeAccrualTracker(10, Map.of());
        tracker.connect(uuid, 0L, 1L);
        assertEquals(0, tracker.disconnect(uuid, 30L * SECOND).completedMinutes());

        tracker.connect(uuid, 100L * SECOND, 2L);
        var result = tracker.disconnect(uuid, 130L * SECOND);
        assertEquals(1, result.completedMinutes());
        assertEquals(1, result.activeMinutes());
    }

    @Test
    void lagSpikeIsCappedRatherThanCreditingUnlimitedTime() {
        UUID uuid = UUID.randomUUID();
        PlaytimeAccrualTracker tracker = new PlaytimeAccrualTracker(10,
                2L * PlaytimeAccrualTracker.NANOS_PER_MINUTE, Map.of());
        tracker.connect(uuid, 0L, 1L);

        var result = tracker.sample(uuid, 30L * PlaytimeAccrualTracker.NANOS_PER_MINUTE,
                ActivityState.ACTIVE, 1L);
        assertEquals(2, result.completedMinutes());
        assertEquals(2, result.activeMinutes());
    }

    @Test
    void suspiciousActiveGraceIsCappedAtOneMinuteThenCountsAsAfk() {
        UUID uuid = UUID.randomUUID();
        PlaytimeAccrualTracker tracker = new PlaytimeAccrualTracker(10, Map.of());
        tracker.connect(uuid, 0L, 1L);

        var grace = tracker.sample(uuid, 60L * SECOND, ActivityState.SUSPICIOUS, 1L);
        assertEquals(1, grace.activeMinutes());
        assertEquals(0, grace.afkMinutes());

        var blocked = tracker.sample(uuid, 120L * SECOND, ActivityState.SUSPICIOUS, 1L);
        assertEquals(0, blocked.activeMinutes());
        assertEquals(1, blocked.afkMinutes());
        assertTrue(blocked.thresholdCrossed());

        var stillBlocked = tracker.sample(uuid, 180L * SECOND, ActivityState.SUSPICIOUS, 1L);
        assertEquals(0, stillBlocked.activeMinutes());
        assertEquals(1, stillBlocked.afkMinutes());
        assertFalse(stillBlocked.thresholdCrossed());

        tracker.sample(uuid, 181L * SECOND, ActivityState.ACTIVE, 2L);
        var reset = tracker.sample(uuid, 241L * SECOND, ActivityState.SUSPICIOUS, 2L);
        assertEquals(1, reset.activeMinutes());
    }

    @Test
    void reconnectDoesNotClearSuspiciousStreakWithoutRealActivityMarker() {
        UUID uuid = UUID.randomUUID();
        PlaytimeAccrualTracker tracker = new PlaytimeAccrualTracker(1, Map.of());
        tracker.connect(uuid, 0L, 1L);
        assertEquals(1, tracker.sample(uuid, 60L * SECOND, ActivityState.SUSPICIOUS, 1L).activeMinutes());
        tracker.disconnect(uuid, 61L * SECOND);

        tracker.connect(uuid, 100L * SECOND, 1L);
        var guarded = tracker.sample(uuid, 160L * SECOND, ActivityState.ACTIVE, 1L);
        assertEquals(ActivityState.SUSPICIOUS, guarded.state());
        assertEquals(0, guarded.activeMinutes());

        tracker.sample(uuid, 161L * SECOND, ActivityState.ACTIVE, 2L);
        var legitimate = tracker.sample(uuid, 221L * SECOND, ActivityState.ACTIVE, 2L);
        assertEquals(1, legitimate.activeMinutes());
    }

    @Test
    void trivialMovementDoesNotReleaseReconnectGuard() {
        UUID uuid = UUID.randomUUID();
        PlaytimeConfig config = mock(PlaytimeConfig.class);
        when(config.sampling()).thenReturn(new PlaytimeConfig.Sampling(
                20, 60L, 300L,
                new PlaytimeConfig.Suspicion(true, 20L, 25, 0.08D, 10L, 1)));
        when(config.activity()).thenReturn(new PlaytimeConfig.Activity(0L, true, 0.01D, true));

        SessionManager sessionManager = mock(SessionManager.class);
        ActivityTracker activityTracker = new ActivityTracker(
                config, sessionManager, Map.of(), new PerformanceCounters());
        Player player = mock(Player.class);
        Location initial = new Location(null, 0.0D, 64.0D, 0.0D, 0.0F, 0.0F);
        Location moved = new Location(null, 1.0D, 64.0D, 0.0D, 0.0F, 0.0F);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getLocation()).thenReturn(initial);
        activityTracker.bootstrapPlayer(player, 1L);

        long reconnectMarker = activityTracker.getSuspiciousResetMarker(uuid);
        PlaytimeAccrualTracker accrualTracker = new PlaytimeAccrualTracker(1, Map.of());
        accrualTracker.connect(uuid, 0L, reconnectMarker);
        assertEquals(1, accrualTracker.sample(
                uuid, 60L * SECOND, ActivityState.SUSPICIOUS, reconnectMarker).activeMinutes());
        accrualTracker.disconnect(uuid, 60L * SECOND);

        accrualTracker.connect(uuid, 100L * SECOND, reconnectMarker);
        var guarded = accrualTracker.sample(
                uuid, 160L * SECOND, ActivityState.ACTIVE, reconnectMarker);
        assertEquals(ActivityState.SUSPICIOUS, guarded.state());
        assertEquals(0, guarded.activeMinutes());
        assertEquals(reconnectMarker, activityTracker.getSuspiciousResetMarker(uuid));

        PlayerMoveEvent movement = mock(PlayerMoveEvent.class);
        when(movement.getPlayer()).thenReturn(player);
        when(movement.getFrom()).thenReturn(initial);
        when(movement.getTo()).thenReturn(moved);
        activityTracker.onMove(movement);

        long movementMarker = activityTracker.getSuspiciousResetMarker(uuid);
        assertEquals(reconnectMarker, movementMarker);
        var stillGuarded = accrualTracker.sample(
                uuid, 220L * SECOND, ActivityState.ACTIVE, movementMarker);
        assertEquals(ActivityState.SUSPICIOUS, stillGuarded.state());
        assertEquals(0, stillGuarded.activeMinutes());
        assertEquals(3, stillGuarded.suspiciousStreak());
    }

    @Test
    void malformedRuntimeSnapshotReconcilesPendingLedgerWithoutThrowing() {
        UUID uuid = UUID.randomUUID();
        Instant start = Instant.parse("2026-08-06T12:00:00Z");
        PlaytimeAccrualTracker.Snapshot malformed = new PlaytimeAccrualTracker.Snapshot(
                true, 0L, start, ActivityState.ACTIVE,
                2L * PlaytimeAccrualTracker.NANOS_PER_MINUTE,
                List.of(new PlaytimeAccrualTracker.SegmentSnapshot(ActivityState.ACTIVE, 1L, start)),
                0, 1L, false);
        PlaytimeAccrualTracker tracker = new PlaytimeAccrualTracker(10, Map.of(uuid, malformed));

        assertDoesNotThrow(() -> tracker.disconnect(uuid, 1L, start.plusNanos(1L)));
        PlaytimeAccrualTracker.Snapshot reconciled = tracker.snapshot().get(uuid);
        long segmentNanos = reconciled.segments().stream()
                .mapToLong(PlaytimeAccrualTracker.SegmentSnapshot::nanos)
                .sum();
        assertEquals(segmentNanos, reconciled.pendingNanos());
        assertEquals(2L, reconciled.pendingNanos());
    }

    @Test
    void runtimeSnapshotPreservesRemainder() {
        UUID uuid = UUID.randomUUID();
        PlaytimeAccrualTracker first = new PlaytimeAccrualTracker(10, Map.of());
        first.connect(uuid, 0L, 1L);
        first.sample(uuid, 45L * SECOND, ActivityState.ACTIVE, 1L);

        PlaytimeAccrualTracker restored = new PlaytimeAccrualTracker(10, first.snapshot());
        restored.connect(uuid, 100L * SECOND, 1L);
        var result = restored.sample(uuid, 115L * SECOND, ActivityState.ACTIVE, 1L);
        assertEquals(1, result.activeMinutes());
    }

    @Test
    void laggedCreditsRetainTheirEarnedTimestampsAcrossHourBoundary() {
        UUID uuid = UUID.randomUUID();
        PlaytimeAccrualTracker tracker = new PlaytimeAccrualTracker(10, Map.of());
        Instant connectedAt = Instant.parse("2026-08-06T11:58:30Z");
        tracker.connect(uuid, 0L, connectedAt, 1L);

        var result = tracker.sample(uuid, 5L * PlaytimeAccrualTracker.NANOS_PER_MINUTE,
                connectedAt.plusSeconds(300), ActivityState.ACTIVE, 1L);

        assertEquals(5, result.activeMinutes());
        assertEquals(List.of(
                Instant.parse("2026-08-06T11:59:30Z"),
                Instant.parse("2026-08-06T12:00:30Z"),
                Instant.parse("2026-08-06T12:01:30Z"),
                Instant.parse("2026-08-06T12:02:30Z"),
                Instant.parse("2026-08-06T12:03:30Z")),
                result.credits().stream().map(PlaytimeAccrualTracker.MinuteCredit::creditedAt).toList());
    }

}
