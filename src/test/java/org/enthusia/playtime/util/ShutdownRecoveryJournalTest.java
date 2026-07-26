package org.enthusia.playtime.util;

import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
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
        verify(restoredRepository).batchRecordMinutes(anyMap(), any());
        assertFalse(temp.resolve("shutdown-recovery.yml").toFile().exists());
    }
}
