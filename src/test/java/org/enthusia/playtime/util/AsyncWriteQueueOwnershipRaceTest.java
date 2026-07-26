package org.enthusia.playtime.util;

import org.bukkit.scheduler.BukkitTask;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.WriteBatch;
import org.enthusia.playtime.data.RecoveryApplyResult;
import org.enthusia.playtime.data.model.PlayerProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AsyncWriteQueueOwnershipRaceTest {
    @Test
    void pauseAbortCommitAndClosureHaveExactOwnershipAndTaskCounts() {
        Fixture f = new Fixture();
        UUID player = UUID.randomUUID();
        f.queue.start();
        assertEquals(1, f.scheduler.periodicStarts);
        assertEquals(AsyncWriteQueue.EnqueueResult.ACCEPTED, f.queue.enqueueMinute(player, 1, 0));
        assertEquals(AsyncWriteQueue.TransitionResult.SUCCESS, f.queue.prepareHandoff(1));
        assertEquals(AsyncWriteQueue.EnqueueResult.HANDOFF_PAUSED, f.queue.enqueueMinute(player, 1, 0));
        assertEquals(AsyncWriteQueue.EnqueueResult.HANDOFF_PAUSED,
                f.queue.enqueueJoin(player, Instant.EPOCH));
        assertEquals(AsyncWriteQueue.EnqueueResult.HANDOFF_PAUSED,
                f.queue.enqueuePlayerProfile(profile(player)));
        f.queue.abortHandoff();
        assertEquals(2, f.scheduler.periodicStarts);
        assertEquals(AsyncWriteQueue.EnqueueResult.ACCEPTED, f.queue.enqueueMinute(player, 1, 0));
        assertEquals(AsyncWriteQueue.TransitionResult.SUCCESS, f.queue.prepareHandoff(1));
        assertEquals(AsyncWriteQueue.TransitionResult.HANDOFF_ABORTED, f.queue.prepareHandoff(1));
        assertEquals(AsyncWriteQueue.TransitionResult.SUCCESS, f.queue.completeHandoff());
        f.queue.abortHandoff();
        f.queue.start();
        assertEquals(2, f.scheduler.periodicStarts);
        assertEquals(AsyncWriteQueue.EnqueueResult.CLOSED, f.queue.enqueueMinute(player, 1, 0));
        assertTrue(f.scheduler.tasks.stream().allMatch(BukkitTask::isCancelled));
    }

    @Test
    void writeFailureRequeuesAndSuccessfulRetryCommitsExactlyOnce() throws Exception {
        Fixture f = new Fixture();
        UUID player = UUID.randomUUID();
        doThrow(new java.sql.SQLException("injected")).doNothing()
                .when(f.repository).batchRecordMinutes(anyMap(), any());
        assertEquals(AsyncWriteQueue.EnqueueResult.ACCEPTED, f.queue.enqueueMinute(player, 1, 0));
        assertEquals(AsyncWriteQueue.TransitionResult.WRITE_FAILED, f.queue.flushNow());
        assertEquals(1, f.queue.getPendingTotals(player).activeMinutes);
        assertEquals(AsyncWriteQueue.TransitionResult.SUCCESS, f.queue.flushNow());
        assertEquals(0, f.queue.getPendingTotals(player).activeMinutes);
        assertEquals(0, f.queue.getAcceptedUncommittedTotals(player).activeMinutes);
        verify(f.repository, times(2)).batchRecordMinutes(anyMap(), any());
        org.mockito.ArgumentCaptor<WriteBatch> batches = org.mockito.ArgumentCaptor.forClass(WriteBatch.class);
        verify(f.repository, times(2)).applyWriteBatch(batches.capture());
        assertEquals(batches.getAllValues().get(0).aggregationTime(), batches.getAllValues().get(1).aggregationTime());
    }

    @Test
    void immediateAndPeriodicFlushRacesDoNotDuplicateOwnership() throws Exception {
        Fixture f = new Fixture();
        UUID player = UUID.randomUUID();
        f.queue.start();
        assertEquals(AsyncWriteQueue.EnqueueResult.ACCEPTED,
                f.queue.enqueueJoinWithProfile(player, Instant.EPOCH, profile(player)));
        assertEquals(1, f.scheduler.immediate.size());
        assertEquals(AsyncWriteQueue.TransitionResult.SUCCESS, f.queue.prepareHandoff(1));
        f.scheduler.immediate.removeFirst().run();
        verify(f.repository, times(1)).batchRecordJoins(anyList());
        verify(f.repository, times(1)).batchUpsertPlayerProfiles(anyList());

        Fixture blocked = new Fixture();
        blocked.queue.start();
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch allowWrite = new CountDownLatch(1);
        doAnswer(call -> { writeStarted.countDown(); allowWrite.await(); return null; })
                .when(blocked.repository).batchRecordMinutes(anyMap(), any());
        blocked.queue.enqueueMinute(player, 1, 0);
        Thread flush = new Thread(blocked.scheduler.periodic, "periodic-flush-test");
        flush.start();
        assertTrue(writeStarted.await(1, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, blocked.queue.getPendingTotals(player).activeMinutes);
        assertEquals(1, blocked.queue.getPendingTotalsForServer().activeMinutes);
        AtomicReference<AsyncWriteQueue.TransitionResult> handoff = new AtomicReference<>();
        Thread prepare = new Thread(() -> handoff.set(blocked.queue.prepareHandoff(2)), "handoff-test");
        prepare.start();
        allowWrite.countDown();
        flush.join();
        prepare.join();
        assertEquals(AsyncWriteQueue.TransitionResult.SUCCESS, handoff.get());
        verify(blocked.repository, times(1)).batchRecordMinutes(anyMap(), any());
    }

    @Test
    void shutdownRacingAbortCannotReopenClosedQueue() throws Exception {
        Fixture f = new Fixture();
        f.queue.start();
        assertEquals(AsyncWriteQueue.TransitionResult.SUCCESS, f.queue.prepareHandoff(1));
        CountDownLatch start = new CountDownLatch(1);
        Thread shutdown = new Thread(() -> { await(start); f.queue.shutdown(1); });
        Thread abort = new Thread(() -> { await(start); f.queue.abortHandoff(); });
        shutdown.start();
        abort.start();
        start.countDown();
        shutdown.join();
        abort.join();
        assertEquals(AsyncWriteQueue.QueueState.CLOSED, f.queue.stateForTesting());
        assertEquals(AsyncWriteQueue.EnqueueResult.CLOSED,
                f.queue.enqueueMinute(UUID.randomUUID(), 1, 0));
    }

    @Test
    void shutdownFlushesAllWriteTypesOrReportsFailureWithoutLosingOwnership() throws Exception {
        Fixture success = new Fixture();
        UUID player = UUID.randomUUID();
        success.queue.enqueueMinute(player, 1, 0);
        success.queue.enqueueJoinWithProfile(player, Instant.EPOCH, profile(player));
        assertEquals(AsyncWriteQueue.TransitionResult.SUCCESS, success.queue.shutdown(1));
        assertEquals(new AsyncWriteQueue.OutstandingWork(0, 0, 0),
                success.queue.outstandingWorkForTesting());
        verify(success.repository).batchRecordMinutes(anyMap(), any());
        verify(success.repository).batchRecordJoins(anyList());
        verify(success.repository).batchUpsertPlayerProfiles(anyList());

        Fixture failed = new Fixture();
        doThrow(new java.sql.SQLException("shutdown failure"))
                .when(failed.repository).batchRecordJoins(anyList());
        failed.queue.enqueueMinute(player, 1, 0);
        failed.queue.enqueueJoinWithProfile(player, Instant.EPOCH, profile(player));
        assertEquals(AsyncWriteQueue.TransitionResult.WRITE_FAILED, failed.queue.shutdown(1));
        AsyncWriteQueue.OutstandingWork retained = failed.queue.outstandingWorkForTesting();
        assertEquals(1, retained.minutePlayers());
        assertEquals(1, retained.joins());
        assertEquals(1, retained.profiles());
        assertEquals(AsyncWriteQueue.QueueState.CLOSED, failed.queue.stateForTesting());
    }

    @Test
    void shutdownTimeoutClosesQueueAndLeavesInFlightMinuteOwned() throws Exception {
        Fixture f = new Fixture();
        UUID player = UUID.randomUUID();
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch allowWrite = new CountDownLatch(1);
        doAnswer(call -> {
            writeStarted.countDown();
            allowWrite.await();
            return null;
        }).when(f.repository).batchRecordMinutes(anyMap(), any());
        assertEquals(AsyncWriteQueue.EnqueueResult.ACCEPTED, f.queue.enqueueMinute(player, 1, 0));
        Thread flush = new Thread(f.queue::flushNow, "shutdown-timeout-flush");
        flush.start();
        assertTrue(writeStarted.await(1, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(AsyncWriteQueue.TransitionResult.TIMED_OUT, f.queue.shutdown(0));
        assertEquals(AsyncWriteQueue.QueueState.CLOSED, f.queue.stateForTesting());
        assertEquals(AsyncWriteQueue.EnqueueResult.CLOSED, f.queue.enqueueMinute(player, 1, 0));
        assertEquals(1, f.queue.outstandingWorkForTesting().minutePlayers());

        allowWrite.countDown();
        flush.join();
        assertEquals(new AsyncWriteQueue.OutstandingWork(0, 0, 0),
                f.queue.outstandingWorkForTesting());
        verify(f.repository, times(1)).batchRecordMinutes(anyMap(), any());
    }

    @Test
    void failedOlderProfileBatchCannotOverwriteNewerPendingProfile() throws Exception {
        Fixture f = new Fixture();
        UUID player = UUID.randomUUID();
        PlayerProfile older = new PlayerProfile(player, "Old", "Old", Instant.ofEpochSecond(1));
        PlayerProfile newer = new PlayerProfile(player, "New", "New", Instant.ofEpochSecond(2));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch fail = new CountDownLatch(1);
        doAnswer(call -> { started.countDown(); fail.await(); throw new java.sql.SQLException("profile failure"); })
                .doNothing().when(f.repository).batchUpsertPlayerProfiles(anyList());
        f.queue.enqueuePlayerProfile(older);
        Thread flush = new Thread(f.queue::flushNow, "older-profile-flush");
        flush.start();
        assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS));
        f.queue.enqueuePlayerProfile(newer);
        fail.countDown();
        flush.join();
        assertEquals(AsyncWriteQueue.TransitionResult.SUCCESS, f.queue.flushNow());
        org.mockito.ArgumentCaptor<List<PlayerProfile>> profiles = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(f.repository, times(3)).batchUpsertPlayerProfiles(profiles.capture());
        assertEquals(newer, profiles.getAllValues().get(2).getFirst());
    }

    @Test
    void successfulOlderProfileBatchDoesNotClearNewerPendingProfile() throws Exception {
        Fixture f = new Fixture();
        UUID player = UUID.randomUUID();
        PlayerProfile older = new PlayerProfile(player, "Old", "Old", Instant.ofEpochSecond(1));
        PlayerProfile newer = new PlayerProfile(player, "New", "New", Instant.ofEpochSecond(2));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        doAnswer(call -> { started.countDown(); complete.await(); return null; })
                .when(f.repository).batchUpsertPlayerProfiles(anyList());
        f.queue.enqueuePlayerProfile(older);
        Thread flush = new Thread(f.queue::flushNow, "older-profile-success-flush");
        flush.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        f.queue.enqueuePlayerProfile(newer);
        complete.countDown();
        flush.join();
        assertEquals(AsyncWriteQueue.TransitionResult.SUCCESS, f.queue.flushNow());
        org.mockito.ArgumentCaptor<List<PlayerProfile>> profiles = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(f.repository, times(2)).batchUpsertPlayerProfiles(profiles.capture());
        assertEquals(newer, profiles.getAllValues().get(1).getFirst());
    }

    @Test
    void deferredDatabaseCloseWaitsForBlockedInFlightCommit() throws Exception {
        Fixture f = new Fixture();
        UUID player = UUID.randomUUID();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        CountDownLatch databaseClosed = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        doAnswer(call -> { started.countDown(); complete.await(); return null; })
                .when(f.repository).batchRecordMinutes(anyMap(), any());
        assertEquals(AsyncWriteQueue.EnqueueResult.ACCEPTED, f.queue.enqueueMinute(player, 1, 0));
        Thread flush = new Thread(f.queue::flushNow, "blocked-commit");
        flush.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertEquals(AsyncWriteQueue.TransitionResult.TIMED_OUT, f.queue.shutdown(0));
        f.queue.closeDatabaseAfterFlush(() -> { closed.set(true); databaseClosed.countDown(); }, 1);
        assertFalse(databaseClosed.await(100, TimeUnit.MILLISECONDS));
        complete.countDown();
        flush.join();
        assertTrue(databaseClosed.await(2, TimeUnit.SECONDS));
        assertTrue(closed.get());
        verify(f.repository, times(1)).batchRecordMinutes(anyMap(), any());
    }

    private static PlayerProfile profile(UUID uuid) {
        return new PlayerProfile(uuid, "Player", null, Instant.EPOCH);
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static final class Fixture {
        final PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        final PlaytimeRepository repository = mock(PlaytimeRepository.class);
        final RecordingScheduler scheduler = new RecordingScheduler();
        final AsyncWriteQueue queue;
        Fixture() {
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            try {
                doAnswer(call -> {
                    WriteBatch batch = call.getArgument(0);
                    if (!batch.joins().isEmpty()) repository.batchRecordJoins(batch.joins());
                    if (!batch.profiles().isEmpty()) repository.batchUpsertPlayerProfiles(new ArrayList<>(batch.profiles().values()));
                    if (!batch.minutes().isEmpty()) repository.batchRecordMinutes(batch.minutes(), batch.aggregationTime());
                    return RecoveryApplyResult.APPLIED;
                }).when(repository).applyWriteBatch(any());
            } catch (java.sql.SQLException exception) {
                throw new AssertionError(exception);
            }
            queue = new AsyncWriteQueue(plugin, repository, new PerformanceCounters(), 20L, scheduler);
        }
    }

    private static final class RecordingScheduler implements AsyncWriteQueue.QueueScheduler {
        int periodicStarts;
        Runnable periodic;
        final java.util.ArrayDeque<Runnable> immediate = new java.util.ArrayDeque<>();
        final List<BukkitTask> tasks = new ArrayList<>();
        @Override public BukkitTask schedulePeriodic(Runnable task, long intervalTicks) {
            periodicStarts++;
            periodic = task;
            BukkitTask handle = mock(BukkitTask.class);
            doAnswer(call -> { when(handle.isCancelled()).thenReturn(true); return null; })
                    .when(handle).cancel();
            tasks.add(handle);
            return handle;
        }
        @Override public void scheduleImmediate(Runnable task) { immediate.addLast(task); }
    }
}
