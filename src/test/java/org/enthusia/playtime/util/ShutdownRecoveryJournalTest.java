package org.enthusia.playtime.util;

import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.RecoveryApplyResult;
import org.enthusia.playtime.data.WriteBatch;
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
                .when(failedRepository).applyWriteBatch(any());
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
        verify(restoredRepository).applyWriteBatch(any());
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
        AsyncWriteQueue.RecoveryJournalSnapshot recovery = new AsyncWriteQueue.RecoveryJournalSnapshot(java.util.List.of(new WriteBatch(UUID.randomUUID(), Instant.EPOCH,
                java.util.Map.of(recoveredPlayer, new MinuteDelta(2, 0)), java.util.Map.of(), java.util.List.of())));
        when(repository.applyWriteBatch(any())).thenReturn(RecoveryApplyResult.ALREADY_APPLIED);
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
        verify(repository, times(2)).applyWriteBatch(any());
        assertEquals(1, settled.get());
        assertEquals(new AsyncWriteQueue.OutstandingWork(0, 0, 0), queue.outstandingWorkForTesting());

        assertEquals(AsyncWriteQueue.TransitionResult.SUCCESS, queue.flushNow());
        verify(repository, times(2)).applyWriteBatch(any());
        assertEquals(new AsyncWriteQueue.OutstandingWork(0, 0, 0), queue.outstandingWorkForTesting());
    }

    @Test
    void shutdownSnapshotKeepsRestoredRecoveryAndNewLiveBatchSeparate() {
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        PlaytimeRepository repository = mock(PlaytimeRepository.class);
        UUID recoveredPlayer = UUID.randomUUID();
        UUID livePlayer = UUID.randomUUID();
        UUID recoveredId = UUID.randomUUID();
        AsyncWriteQueue queue = new AsyncWriteQueue(plugin, repository, new PerformanceCounters(), 20L,
                new AsyncWriteQueue.QueueScheduler() {
                    @Override public org.bukkit.scheduler.BukkitTask schedulePeriodic(Runnable task, long intervalTicks) { return null; }
                    @Override public void scheduleImmediate(Runnable task) { }
                });
        queue.restoreRecoverySnapshot(new AsyncWriteQueue.RecoveryJournalSnapshot(java.util.List.of(new WriteBatch(recoveredId,
                Instant.ofEpochSecond(10), java.util.Map.of(recoveredPlayer, new MinuteDelta(2, 0)), java.util.Map.of(), java.util.List.of()))), () -> { });
        queue.enqueueMinute(livePlayer, 1, 0);
        queue.enqueueJoin(livePlayer, Instant.ofEpochSecond(20));
        queue.enqueuePlayerProfile(new PlayerProfile(livePlayer, "Live", null, Instant.ofEpochSecond(20)));

        AsyncWriteQueue.RecoveryJournalSnapshot snapshot = queue.recoverySnapshot();
        assertEquals(2, snapshot.batches().size());
        assertEquals(recoveredId, snapshot.batches().get(0).batchId());
        assertEquals(Instant.ofEpochSecond(10), snapshot.batches().get(0).aggregationTime());
        assertNotEquals(recoveredId, snapshot.batches().get(1).batchId());
        assertEquals(1, snapshot.batches().get(1).minutes().get(livePlayer).activeMinutes());
        assertEquals(1, snapshot.batches().get(1).joins().size());
        assertEquals("Live", snapshot.batches().get(1).profiles().get(livePlayer).username());
        assertEquals(new AsyncWriteQueue.OutstandingWork(2, 1, 1), queue.outstandingWorkForTesting());

        AsyncWriteQueue.RecoveryJournalSnapshot repeated = queue.recoverySnapshot();
        assertEquals(2, repeated.batches().size());
        assertEquals(snapshot.batches().get(1).batchId(), repeated.batches().get(1).batchId());
        assertEquals(snapshot.batches().get(1).aggregationTime(), repeated.batches().get(1).aggregationTime());
        assertEquals(snapshot.batches().get(1).minutes(), repeated.batches().get(1).minutes());
        assertEquals(snapshot.batches().get(1).profiles(), repeated.batches().get(1).profiles());
        assertEquals(snapshot.batches().get(1).joins(), repeated.batches().get(1).joins());
        assertEquals(new AsyncWriteQueue.OutstandingWork(2, 1, 1), queue.outstandingWorkForTesting());
    }

    @Test
    void formatThreeRoundTripRestoresAllBatchContentsInOrderBeforeDeletion() throws Exception {
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        UUID minutePlayer = UUID.randomUUID();
        UUID profilePlayer = UUID.randomUUID();
        UUID joinPlayer = UUID.randomUUID();
        WriteBatch first = new WriteBatch(UUID.randomUUID(), Instant.ofEpochSecond(100),
                java.util.Map.of(minutePlayer, new MinuteDelta(3, 2)),
                java.util.Map.of(profilePlayer, new PlayerProfile(profilePlayer, "Profile", "Display", Instant.ofEpochSecond(101))),
                java.util.List.of(new JoinRecord(joinPlayer, Instant.ofEpochSecond(102))));
        WriteBatch second = new WriteBatch(UUID.randomUUID(), Instant.ofEpochSecond(200),
                java.util.Map.of(), java.util.Map.of(), java.util.List.of());
        ShutdownRecoveryJournal journal = new ShutdownRecoveryJournal(plugin);
        journal.write(new AsyncWriteQueue.RecoveryJournalSnapshot(java.util.List.of(first, second)));

        PlaytimeRepository repository = mock(PlaytimeRepository.class);
        when(repository.applyWriteBatch(any())).thenReturn(RecoveryApplyResult.APPLIED);
        AsyncWriteQueue queue = new AsyncWriteQueue(plugin, repository, new PerformanceCounters(), 20L);
        journal.restoreInto(queue);
        assertEquals(AsyncWriteQueue.TransitionResult.SUCCESS, queue.flushNow());

        org.mockito.ArgumentCaptor<WriteBatch> batches = org.mockito.ArgumentCaptor.forClass(WriteBatch.class);
        verify(repository, times(2)).applyWriteBatch(batches.capture());
        WriteBatch restoredFirst = batches.getAllValues().get(0);
        WriteBatch restoredSecond = batches.getAllValues().get(1);
        assertEquals(first.batchId(), restoredFirst.batchId());
        assertEquals(first.aggregationTime(), restoredFirst.aggregationTime());
        assertEquals(3, restoredFirst.minutes().get(minutePlayer).activeMinutes());
        assertEquals(2, restoredFirst.minutes().get(minutePlayer).afkMinutes());
        assertEquals(first.profiles().get(profilePlayer), restoredFirst.profiles().get(profilePlayer));
        assertEquals(first.joins(), restoredFirst.joins());
        assertEquals(second.batchId(), restoredSecond.batchId());
        assertEquals(second.aggregationTime(), restoredSecond.aggregationTime());
        assertFalse(temp.resolve("shutdown-recovery.yml").toFile().exists());
    }
    @Test
    void synchronousReplayKeepsJournalUntilEveryBatchIsDurablySettled() throws Exception {
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        WriteBatch first = new WriteBatch(UUID.randomUUID(), Instant.ofEpochSecond(10),
                java.util.Map.of(UUID.randomUUID(), new MinuteDelta(2, 0)),
                java.util.Map.of(), java.util.List.of());
        WriteBatch second = new WriteBatch(UUID.randomUUID(), Instant.ofEpochSecond(20),
                java.util.Map.of(UUID.randomUUID(), new MinuteDelta(3, 0)),
                java.util.Map.of(), java.util.List.of());
        ShutdownRecoveryJournal journal = new ShutdownRecoveryJournal(plugin);
        journal.write(new AsyncWriteQueue.RecoveryJournalSnapshot(java.util.List.of(first, second)));

        PlaytimeRepository repository = mock(PlaytimeRepository.class);
        when(repository.applyWriteBatch(any(WriteBatch.class)))
                .thenReturn(RecoveryApplyResult.APPLIED)
                .thenThrow(new java.sql.SQLException("injected second-batch failure"));

        assertThrows(IllegalStateException.class, () -> journal.replaySynchronously(repository));
        assertTrue(journal.fileForLogging().isFile());
        reset(repository);
        when(repository.applyWriteBatch(any(WriteBatch.class)))
                .thenReturn(RecoveryApplyResult.ALREADY_APPLIED, RecoveryApplyResult.APPLIED);
        assertEquals(2, journal.replaySynchronously(repository));
        assertFalse(journal.fileForLogging().exists());
    }

}
