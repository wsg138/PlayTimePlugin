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

        TierProgressTracker.InitializationRequest request = request(tracker, player);
        assertFalse(tracker.acceptActiveMinutes(player, 1).initialized());
        TierProgressTracker.InitializationResult result = tracker.finishInitialization(request, 59).orElseThrow();

        assertEquals(1, result.withheldActiveMinutes());
        assertEquals("I", result.reachedTier().orElseThrow().label());
        assertTrue(tracker.acceptActiveMinutes(player, 1).reachedTier().isEmpty());
    }

    @Test
    void durableTierAtJoinDoesNotAnnounceButBufferedCrossingDoes() {
        TierProgressTracker tracker = new TierProgressTracker(catalog, Map.of());
        UUID player = UUID.randomUUID();

        assertTrue(tracker.finishInitialization(request(tracker, player), 100).orElseThrow().reachedTier().isEmpty());

        UUID crossingPlayer = UUID.randomUUID();
        TierProgressTracker.InitializationRequest request = request(tracker, crossingPlayer);
        tracker.acceptActiveMinutes(crossingPlayer, 2);
        assertEquals("II", tracker.finishInitialization(request, 89).orElseThrow().reachedTier().orElseThrow().label());
    }

    @Test
    void preservesProgressAcrossReloadAndDoesNotRepeatStaleCrossing() {
        UUID player = UUID.randomUUID();
        TierProgressTracker first = new TierProgressTracker(catalog, Map.of());
        first.finishInitialization(request(first, player), 59);

        TierProgressTracker reloaded = new TierProgressTracker(catalog, first.snapshot());
        assertTrue(reloaded.requestInitialization(player, true).isEmpty());
        assertEquals("I", reloaded.acceptActiveMinutes(player, 1).reachedTier().orElseThrow().label());
        assertTrue(reloaded.acceptActiveMinutes(player, 1).reachedTier().isEmpty());
    }

    @Test
    void onlyActiveMinutesAdvanceAndMultiMinuteUpdatesChooseFinalTier() {
        TierProgressTracker tracker = new TierProgressTracker(catalog, Map.of());
        UUID player = UUID.randomUUID();
        tracker.finishInitialization(request(tracker, player), 59);

        assertTrue(tracker.acceptActiveMinutes(player, 0).reachedTier().isEmpty());
        assertEquals("III", tracker.acceptActiveMinutes(player, 61).reachedTier().orElseThrow().label());
    }

    @Test
    void ignoresStaleCallbacksAfterDisconnectAndReconnect() {
        TierProgressTracker tracker = new TierProgressTracker(catalog, Map.of());
        UUID player = UUID.randomUUID();
        TierProgressTracker.InitializationRequest first = request(tracker, player);
        tracker.acceptActiveMinutes(player, 1);
        tracker.disconnect(player);
        tracker.reconnect(player);
        TierProgressTracker.InitializationRequest second = request(tracker, player);

        assertTrue(tracker.finishInitialization(first, 59).isEmpty());
        assertEquals("I", tracker.finishInitialization(second, 59).orElseThrow().reachedTier().orElseThrow().label());
    }

    @Test
    void retainsDisconnectedUninitializedMinutesForReloadRecovery() {
        TierProgressTracker tracker = new TierProgressTracker(catalog, Map.of());
        UUID player = UUID.randomUUID();
        request(tracker, player);
        tracker.acceptActiveMinutes(player, 1);
        tracker.disconnect(player);

        TierProgressTracker reloaded = new TierProgressTracker(catalog, tracker.snapshot());
        TierProgressTracker.InitializationRequest request = reloaded.requestInitialization(player, false).orElseThrow();
        TierProgressTracker.InitializationResult result = reloaded.finishInitialization(request, 59).orElseThrow();
        assertEquals(1, result.withheldActiveMinutes());
        assertFalse(result.connected());
        assertEquals("I", result.reachedTier().orElseThrow().label());
    }

    private TierProgressTracker.InitializationRequest request(TierProgressTracker tracker, UUID player) {
        return tracker.requestInitialization(player, true).orElseThrow();
    }
}
