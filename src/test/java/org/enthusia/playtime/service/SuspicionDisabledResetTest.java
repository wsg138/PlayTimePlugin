package org.enthusia.playtime.service;

import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.activity.ActivityState;
import org.enthusia.playtime.activity.ActivityTracker;
import org.enthusia.playtime.activity.SessionManager;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.enthusia.playtime.util.PerformanceCounters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuspicionDisabledResetTest {
    private PlayTimePlugin plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.load(PlayTimePlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void disablingSuspicionReleasesRetainedAccrualGuard() {
        plugin.getConfig().set("sampling.suspicion.enabled", false);
        PlaytimeConfig disabledConfig = PlaytimeConfig.load(plugin);

        UUID uuid = UUID.randomUUID();
        long oldMarker = System.currentTimeMillis() - 10_000L;
        ActivityTracker activityTracker = new ActivityTracker(
                disabledConfig,
                new SessionManager(),
                Map.of(),
                Map.of(uuid, oldMarker),
                new PerformanceCounters());

        PlaytimeAccrualTracker.Snapshot guardedSnapshot = new PlaytimeAccrualTracker.Snapshot(
                false,
                0L,
                Instant.EPOCH,
                ActivityState.SUSPICIOUS,
                0L,
                List.of(),
                2,
                oldMarker,
                true);
        PlaytimeAccrualTracker accrualTracker = new PlaytimeAccrualTracker(
                1, Map.of(uuid, guardedSnapshot));

        long disabledMarker = activityTracker.getSuspiciousResetMarker(uuid);
        assertTrue(disabledMarker > oldMarker);

        accrualTracker.connect(uuid, 0L, Instant.EPOCH, disabledMarker);
        PlaytimeAccrualTracker.AccrualResult result = accrualTracker.sample(
                uuid,
                PlaytimeAccrualTracker.NANOS_PER_MINUTE,
                Instant.EPOCH.plusSeconds(60L),
                ActivityState.ACTIVE,
                activityTracker.getSuspiciousResetMarker(uuid));

        assertEquals(ActivityState.ACTIVE, result.state());
        assertEquals(1, result.activeMinutes());
        assertEquals(0, result.afkMinutes());
        assertEquals(0, result.suspiciousStreak());
    }
}
