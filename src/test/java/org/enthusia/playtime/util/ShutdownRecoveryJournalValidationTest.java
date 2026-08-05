package org.enthusia.playtime.util;

import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShutdownRecoveryJournalValidationTest {
    @TempDir
    Path temporaryDirectory;

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
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        PlaytimeRepository repository = mock(PlaytimeRepository.class);
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
