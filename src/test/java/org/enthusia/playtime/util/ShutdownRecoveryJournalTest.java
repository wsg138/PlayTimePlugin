package org.enthusia.playtime.util;

import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.RecoveryApplyResult;
import org.enthusia.playtime.data.PlaytimeRepository.JoinRecord;
import org.enthusia.playtime.data.model.MinuteDelta;
import org.enthusia.playtime.data.model.PlayerProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

class ShutdownRecoveryJournalTest {
    @TempDir Path temp;

    @Test
    void failedClosedQueueIsJournaledAndRestoredExactlyOnceBeforeDeletion() throws Exception {
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        PlaytimeRepository failedRepository = mock(PlaytimeRepository.class);
        doThrow(new java.sql.SQLException("injected failure"))
                .when(failedRepository).batchRecordMinutes(anyMap(), any());
        AsyncWriteQueue failed = new AsyncWriteQueue(plugin, failedRepository, new PerformanceCounters(), 20L);
        UUID player = UUID.randomUUID();
        assertEquals(AsyncWriteQueue.EnqueueResult.ACCEPTED, failed.enqueueMinute(player, 2, 0));
        assertEquals(AsyncWriteQueue.TransitionResult.WRITE_FAILED, failed.flushNow());
        assertEquals(AsyncWriteQueue.TransitionResult.WRITE_FAILED, failed.shutdown(0));

        ShutdownRecoveryJournal journal = new ShutdownRecoveryJournal(plugin);
        CountDownLatch databaseClosed = new CountDownLatch(1);
        failed.closeDatabaseAfterFlush(databaseClosed::countDown, 1, journal::write);
        assertTrue(databaseClosed.await(2, TimeUnit.SECONDS));
        assertTrue(temp.resolve("shutdown-recovery.yml").toFile().isFile());

        PlaytimeRepository restoredRepository = mock(PlaytimeRepository.class);
        AsyncWriteQueue restored = new AsyncWriteQueue(plugin, restoredRepository, new PerformanceCounters(), 20L);
        journal.restoreInto(restored);
        assertEquals(AsyncWriteQueue.TransitionResult.SUCCESS, restored.flushNow());
        verify(restoredRepository).applyRecoveryBatch(any());
        assertFalse(temp.resolve("shutdown-recovery.yml").toFile().exists());
    }

    @Test
    void alreadyAppliedRecoveryCannotConsumeLiveWritesAcceptedBeforeSettlement() throws Exception {
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        PlaytimeRepository repository = mock(PlaytimeRepository.class);
        UUID recoveredPlayer = UUID.randomUUID();
        UUID livePlayer = UUID.randomUUID();
        AsyncWriteQueue.RecoverySnapshot recovery = new AsyncWriteQueue.RecoverySnapshot(UUID.randomUUID(),
                java.util.Map.of(recoveredPlayer, new MinuteDelta(2, 0)), java.util.Map.of(), java.util.List.of());
        when(repository.applyRecoveryBatch(recovery)).thenReturn(RecoveryApplyResult.ALREADY_APPLIED);
        AsyncWriteQueue queue = new AsyncWriteQueue(plugin, repository, new PerformanceCounters(), 20L,
                new AsyncWriteQueue.QueueScheduler() {
                    @Override public org.bukkit.scheduler.BukkitTask schedulePeriodic(Runnable task, long intervalTicks) { return null; }
                    @Override public void scheduleImmediate(Runnable task) { }
                });
        java.util.concurrent.atomic.AtomicInteger settled = new java.util.concurrent.atomic.AtomicInteger();
        queue.restoreRecoverySnapshot(recovery, settled::incrementAndGet);
        PlayerProfile newer = new PlayerProfile(livePlayer, "Live", null, Instant.ofEpochSecond(2));
        queue.enqueueMinute(livePlayer, 1, 0);
        queue.enqueueJoin(livePlayer, Instant.EPOCH);
        queue.enqueuePlayerProfile(newer);

        assertEquals(AsyncWriteQueue.TransitionResult.SUCCESS, queue.flushNow());
        verify(repository).applyRecoveryBatch(recovery);
        assertEquals(1, settled.get());
        assertEquals(new AsyncWriteQueue.OutstandingWork(1, 1, 1), queue.outstandingWorkForTesting());

        assertEquals(AsyncWriteQueue.TransitionResult.SUCCESS, queue.flushNow());
        verify(repository).batchRecordMinutes(anyMap(), any());
        verify(repository).batchRecordJoins(java.util.List.of(new JoinRecord(livePlayer, Instant.EPOCH)));
        verify(repository).batchUpsertPlayerProfiles(java.util.List.of(newer));
        assertEquals(new AsyncWriteQueue.OutstandingWork(0, 0, 0), queue.outstandingWorkForTesting());
    }
}
