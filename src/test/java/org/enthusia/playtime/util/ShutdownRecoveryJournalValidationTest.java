package org.enthusia.playtime.util;

import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.RecoveryApplyResult;
import org.enthusia.playtime.data.WriteBatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShutdownRecoveryJournalValidationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void validLegacyFormatTwoRestoresContentsAndDeletesAfterDurableFlush() throws Exception {
        UUID batchId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID minutePlayer = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID profilePlayer = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID joinPlayer = UUID.fromString("00000000-0000-0000-0000-000000000003");
        Path journalFile = writeJournal("""
                format: 2
                createdAt: 1000
                batchId: 00000000-0000-0000-0000-000000000010
                aggregationTime: 2000
                minutes:
                  00000000-0000-0000-0000-000000000001:
                    active: 3
                    afk: 2
                profiles:
                  00000000-0000-0000-0000-000000000002:
                    username: Profile
                    displayName: Display
                    seenAt: 3000
                joins:
                  - uuid: 00000000-0000-0000-0000-000000000003
                    joinedAt: 4000
                """);
        PlaytimeRepository repository = mock(PlaytimeRepository.class);
        when(repository.applyWriteBatch(any())).thenReturn(RecoveryApplyResult.APPLIED);
        AsyncWriteQueue queue = queue(repository);

        journal().restoreInto(queue);
        assertTrue(Files.exists(journalFile));
        assertEquals(AsyncWriteQueue.TransitionResult.SUCCESS, queue.flushNow());

        ArgumentCaptor<WriteBatch> captor = ArgumentCaptor.forClass(WriteBatch.class);
        verify(repository, times(1)).applyWriteBatch(captor.capture());
        WriteBatch restored = captor.getValue();
        assertEquals(batchId, restored.batchId());
        assertEquals(Instant.ofEpochMilli(2000), restored.aggregationTime());
        assertEquals(3L, restored.minutes().get(minutePlayer).activeMinutes());
        assertEquals(2L, restored.minutes().get(minutePlayer).afkMinutes());
        assertEquals("Profile", restored.profiles().get(profilePlayer).username());
        assertEquals("Display", restored.profiles().get(profilePlayer).displayName());
        assertEquals(Instant.ofEpochMilli(3000), restored.profiles().get(profilePlayer).seenAt());
        assertEquals(joinPlayer, restored.joins().getFirst().uuid());
        assertEquals(Instant.ofEpochMilli(4000), restored.joins().getFirst().joinedAt());
        assertFalse(Files.exists(journalFile));
    }

    @Test
    void malformedJournalStopsRecoveryAndRemainsUntouched() throws Exception {
        String malformed = "format: 3\ncreatedAt: 1\nbatches: [\n";
        Path journalFile = writeJournal(malformed);

        assertThrows(IllegalStateException.class, () -> journal().restoreInto(queue()));
        assertTrue(Files.exists(journalFile));
        assertEquals(malformed, Files.readString(journalFile));
    }

    @Test
    void unsupportedFormatStopsRecoveryAndRemainsUntouched() throws Exception {
        Path journalFile = writeJournal("format: 99\ncreatedAt: 1\nbatches: []\n");

        assertThrows(IllegalStateException.class, () -> journal().restoreInto(queue()));
        assertTrue(Files.exists(journalFile));
    }

    @Test
    void missingBatchIdStopsRecoveryInsteadOfInventingOne() throws Exception {
        Path journalFile = writeJournal("""
                format: 3
                createdAt: 1
                batches:
                  - aggregationTime: 1
                    minutes:
                      00000000-0000-0000-0000-000000000001:
                        active: 2
                        afk: 0
                    profiles: {}
                    joins: []
                """);

        assertThrows(IllegalStateException.class, () -> journal().restoreInto(queue()));
        assertTrue(Files.exists(journalFile));
    }

    @Test
    void partialInvalidEntryStopsWholeRecovery() throws Exception {
        Path journalFile = writeJournal("""
                format: 3
                createdAt: 1
                batches:
                  - batchId: 00000000-0000-0000-0000-000000000010
                    aggregationTime: 1
                    minutes:
                      not-a-uuid:
                        active: 2
                        afk: 0
                    profiles: {}
                    joins: []
                """);

        assertThrows(IllegalStateException.class, () -> journal().restoreInto(queue()));
        assertTrue(Files.exists(journalFile));
    }

    @Test
    void legacyJournalRejectsScalarMinutesSection() throws Exception {
        Path journalFile = writeJournal("""
                format: 2
                createdAt: 1
                batchId: 00000000-0000-0000-0000-000000000010
                aggregationTime: 1
                minutes: broken
                profiles: {}
                joins: []
                """);

        assertThrows(IllegalStateException.class, () -> journal().restoreInto(queue()));
        assertTrue(Files.exists(journalFile));
    }

    @Test
    void legacyJournalRejectsWrongJoinType() throws Exception {
        Path journalFile = writeJournal("""
                format: 2
                createdAt: 1
                batchId: 00000000-0000-0000-0000-000000000010
                aggregationTime: 1
                minutes: {}
                profiles: {}
                joins: broken
                """);

        assertThrows(IllegalStateException.class, () -> journal().restoreInto(queue()));
        assertTrue(Files.exists(journalFile));
    }

    @Test
    void fractionalMinuteValuesAreRejectedInsteadOfTruncated() throws Exception {
        Path journalFile = writeJournal("""
                format: 3
                createdAt: 1
                batches:
                  - batchId: 00000000-0000-0000-0000-000000000010
                    aggregationTime: 1
                    minutes:
                      00000000-0000-0000-0000-000000000001:
                        active: 1.5
                        afk: 0
                    profiles: {}
                    joins: []
                """);

        assertThrows(IllegalStateException.class, () -> journal().restoreInto(queue()));
        assertTrue(Files.exists(journalFile));
    }

    private Path writeJournal(String content) throws Exception {
        Path journalFile = temporaryDirectory.resolve("shutdown-recovery.yml");
        Files.writeString(journalFile, content);
        return journalFile;
    }

    private ShutdownRecoveryJournal journal() {
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        return new ShutdownRecoveryJournal(plugin);
    }

    private AsyncWriteQueue queue() {
        return queue(mock(PlaytimeRepository.class));
    }

    private AsyncWriteQueue queue(PlaytimeRepository repository) {
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        return new AsyncWriteQueue(plugin, repository, new PerformanceCounters(), 20L,
                new AsyncWriteQueue.QueueScheduler() {
                    @Override
                    public org.bukkit.scheduler.BukkitTask schedulePeriodic(Runnable task, long intervalTicks) {
                        return null;
                    }

                    @Override
                    public void scheduleImmediate(Runnable task) {
                    }
                });
    }
}
