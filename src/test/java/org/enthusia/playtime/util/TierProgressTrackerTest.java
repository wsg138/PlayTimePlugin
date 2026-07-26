package org.enthusia.playtime.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TierProgressTrackerTest {
    private final NumeralTierCatalog catalog = new NumeralTierCatalog(List.of(
            new NumeralTierCatalog.Tier("III", 120, "&b"),
            new NumeralTierCatalog.Tier("I", 60, "&7"),
            new NumeralTierCatalog.Tier("II", 90, "&a")));

    @Test
    void coldBaselineReconcilesThresholdCrossingExactlyOnce() {
        TierProgressTracker tracker = new TierProgressTracker(catalog, Map.of());
        UUID player = UUID.randomUUID();

        assertTrue(tracker.needsInitialization(player));
        assertFalse(tracker.acceptActiveMinutes(player, 1).initialized());
        TierProgressTracker.InitializationResult result = tracker.finishInitialization(player, 59);

        assertEquals(1, result.withheldActiveMinutes());
        assertEquals("I", result.reachedTier().orElseThrow().label());
        assertTrue(tracker.acceptActiveMinutes(player, 1).reachedTier().isEmpty());
    }

    @Test
    void durableTierAtJoinDoesNotAnnounceButBufferedCrossingDoes() {
        TierProgressTracker tracker = new TierProgressTracker(catalog, Map.of());
        UUID player = UUID.randomUUID();

        tracker.needsInitialization(player);
        assertTrue(tracker.finishInitialization(player, 100).reachedTier().isEmpty());

        UUID crossingPlayer = UUID.randomUUID();
        tracker.needsInitialization(crossingPlayer);
        tracker.acceptActiveMinutes(crossingPlayer, 2);
        assertEquals("II", tracker.finishInitialization(crossingPlayer, 89).reachedTier().orElseThrow().label());
    }

    @Test
    void preservesProgressAcrossReloadAndDoesNotRepeatStaleCrossing() {
        UUID player = UUID.randomUUID();
        TierProgressTracker first = new TierProgressTracker(catalog, Map.of());
        first.needsInitialization(player);
        first.finishInitialization(player, 59);

        TierProgressTracker reloaded = new TierProgressTracker(catalog, first.snapshot());
        assertFalse(reloaded.needsInitialization(player));
        assertEquals("I", reloaded.acceptActiveMinutes(player, 1).reachedTier().orElseThrow().label());
        assertTrue(reloaded.acceptActiveMinutes(player, 1).reachedTier().isEmpty());
    }

    @Test
    void onlyActiveMinutesAdvanceAndMultiMinuteUpdatesChooseFinalTier() {
        TierProgressTracker tracker = new TierProgressTracker(catalog, Map.of());
        UUID player = UUID.randomUUID();
        tracker.needsInitialization(player);
        tracker.finishInitialization(player, 59);

        assertTrue(tracker.acceptActiveMinutes(player, 0).reachedTier().isEmpty());
        assertEquals("III", tracker.acceptActiveMinutes(player, 61).reachedTier().orElseThrow().label());
    }
}
