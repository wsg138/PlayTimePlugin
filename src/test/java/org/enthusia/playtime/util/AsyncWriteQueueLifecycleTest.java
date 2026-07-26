package org.enthusia.playtime.util;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsyncWriteQueueLifecycleTest {
    @Test
    void handoffRejectsOwnershipAbortRestoresItAndCommitClosesPermanently() {
        AsyncWriteQueue queue = new AsyncWriteQueue(null, null, new PerformanceCounters(), 20L);
        UUID player = UUID.randomUUID();
        assertEquals(AsyncWriteQueue.TransitionResult.SUCCESS, queue.prepareHandoff(1));
        assertEquals(AsyncWriteQueue.EnqueueResult.HANDOFF_PAUSED, queue.enqueueMinute(player, 1, 0));
        queue.abortHandoff();
        assertEquals(AsyncWriteQueue.EnqueueResult.ACCEPTED, queue.enqueueMinute(player, 1, 0));

        AsyncWriteQueue empty = new AsyncWriteQueue(null, null, new PerformanceCounters(), 20L);
        assertEquals(AsyncWriteQueue.TransitionResult.SUCCESS, empty.prepareHandoff(1));
        assertEquals(AsyncWriteQueue.EnqueueResult.HANDOFF_PAUSED, empty.enqueueMinute(player, 1, 0));
        assertEquals(AsyncWriteQueue.TransitionResult.SUCCESS, empty.completeHandoff());
        empty.start();
        empty.abortHandoff();
        assertEquals(AsyncWriteQueue.QueueState.CLOSED, empty.stateForTesting());
        assertEquals(AsyncWriteQueue.EnqueueResult.CLOSED, empty.enqueueMinute(player, 1, 0));
    }

    @Test
    void acceptedMinuteAfterSnapshotForcesRetryAtConsistentSixtyMinuteCutoff() {
        UUID player = UUID.randomUUID();
        AsyncWriteQueue queue = new AsyncWriteQueue(null, null, new PerformanceCounters(), 20L);
        TierProgressTracker tracker = new TierProgressTracker(
                new NumeralTierCatalog(java.util.List.of(new NumeralTierCatalog.Tier("I", 60, "&7"))),
                Map.of());

        TierProgressTracker.InitializationRequest first = tracker.requestInitialization(player, true, 0L).orElseThrow();
        AsyncWriteQueue.EffectiveActiveSnapshot stale =
                queue.readEffectiveActiveSnapshot(player, () -> 59L).orElseThrow();
        assertEquals(AsyncWriteQueue.EnqueueResult.ACCEPTED, queue.enqueueMinute(player, 1, 0));
        tracker.acceptActiveMinutes(player, 1);
        assertEquals(0L, stale.acceptedActiveSequence());
        assertEquals(1L, queue.acceptedActiveSequence(player));
        tracker.failInitialization(first);

        TierProgressTracker.InitializationRequest retry =
                tracker.requestInitialization(player, true, queue.acceptedActiveSequence(player)).orElseThrow();
        AsyncWriteQueue.EffectiveActiveSnapshot current =
                queue.readEffectiveActiveSnapshot(player, () -> 59L).orElseThrow();
        assertEquals(60L, current.activeMinutes());
        TierProgressTracker.InitializationResult result =
                tracker.finishInitialization(retry, current.activeMinutes()).orElseThrow();
        assertEquals("I", result.reachedTier().orElseThrow().label());
        assertEquals(0, tracker.acceptActiveMinutes(player, 0).reachedTier().stream().count());
    }
}
