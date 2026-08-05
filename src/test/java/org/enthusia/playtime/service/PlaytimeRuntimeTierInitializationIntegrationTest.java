package org.enthusia.playtime.service;

import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.model.MinuteDelta;
import org.enthusia.playtime.data.RecoveryApplyResult;
import org.enthusia.playtime.data.WriteBatch;
import org.enthusia.playtime.util.AsyncWriteQueue;
import org.enthusia.playtime.util.TierProgressTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlaytimeRuntimeTierInitializationIntegrationTest {
    private static final long ASYNC_TEST_TIMEOUT_SECONDS = 5L;
    private PlayTimePlugin plugin;

    @BeforeEach void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.load(PlayTimePlugin.class);
        plugin.getConfig().set("numerals.tier-up-announcement.enabled", true);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());
    }

    @AfterEach void tearDown() {
        PlaytimeRuntime.setTierReadProbeForTesting(null);
        PlaytimeRuntime.setAnnouncementProbeForTesting(null);
        PlaytimeRuntime.setTierMainExecutorForTesting(null);
        MockBukkit.unmock();
    }

    @Test
    void staleSqlCutoffRetriesAtSixtyWithOneOwnedMinuteAndOneAnnouncement() throws Exception {
        PlaytimeRuntime runtime = plugin.runtime();
        UUID uuid = UUID.randomUUID();
        runtime.repository().batchRecordMinutes(Map.of(uuid, new MinuteDelta(59, 0)), Instant.now());
        CountDownLatch readPaused = new CountDownLatch(1);
        CountDownLatch resumeRead = new CountDownLatch(1);
        CountDownLatch firstSqlDone = new CountDownLatch(1);
        CountDownLatch retrySqlDone = new CountDownLatch(1);
        CountDownLatch firstMainScheduled = new CountDownLatch(1);
        CountDownLatch retryMainScheduled = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        AtomicBoolean firstCompletion = new AtomicBoolean(true);
        AtomicBoolean firstMain = new AtomicBoolean(true);
        AtomicInteger announcements = new AtomicInteger();
        ConcurrentLinkedQueue<Runnable> mainCompletions = new ConcurrentLinkedQueue<>();
        PlaytimeRuntime.setTierMainExecutorForTesting(mainCompletions::add);
        PlaytimeRuntime.setAnnouncementProbeForTesting(message -> announcements.incrementAndGet());
        PlaytimeRuntime.setTierReadProbeForTesting(stage -> {
            if (stage == PlaytimeRuntime.TierReadStage.AFTER_CUTOFF_BEFORE_SQL
                    && firstRead.compareAndSet(true, false)) {
                readPaused.countDown();
                await(resumeRead);
            }
            if (stage == PlaytimeRuntime.TierReadStage.AFTER_SQL) {
                if (firstCompletion.compareAndSet(true, false)) firstSqlDone.countDown();
                else retrySqlDone.countDown();
            }
            if (stage == PlaytimeRuntime.TierReadStage.MAIN_COMPLETION_SCHEDULED) {
                if (firstMain.compareAndSet(true, false)) firstMainScheduled.countDown();
                else retryMainScheduled.countDown();
            }
        });

        PlayerMock player = new PlayerMock(MockBukkit.getMock(), "CutoffPlayer", uuid);
        MockBukkit.getMock().addPlayer(player);
        MockBukkit.getMock().getScheduler().performTicks(1);
        assertTrue(readPaused.await(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(runtime.acceptMinuteForTesting(player, 1, 0));
        assertEquals(1, runtime.writeQueue().getAcceptedUncommittedTotals(uuid).activeMinutes);
        assertEquals(1, runtime.writeQueue().acceptedActiveSequence(uuid));

        resumeRead.countDown();
        assertTrue(firstSqlDone.await(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(firstMainScheduled.await(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNotNull(mainCompletions.peek());
        mainCompletions.remove().run();
        TierProgressTracker.ProgressState afterStale = runtime.tierProgressForTesting(uuid);
        assertNotNull(afterStale);
        if (!afterStale.initialized()) {
            assertFalse(afterStale.initializing());
            for (int attempt = 0; attempt < 10 && retryMainScheduled.getCount() > 0; attempt++) {
                runtime.performTierInitializationReadForTesting(uuid, true);
            }
            assertEquals(0, retryMainScheduled.getCount());
            assertNotNull(mainCompletions.peek());
            mainCompletions.remove().run();
        }

        TierProgressTracker.ProgressState progress = runtime.tierProgressForTesting(uuid);
        assertNotNull(progress);
        assertTrue(progress.initialized());
        assertEquals(60, progress.activeMinutes());
        assertEquals(0, runtime.writeQueue().getAcceptedUncommittedTotals(uuid).activeMinutes);
        assertEquals(1, announcements.get());
    }

    @Test
    void recoveryBatchIdMakesJournalReplayExactlyOnce() throws Exception {
        PlaytimeRuntime runtime = plugin.runtime();
        UUID player = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        WriteBatch snapshot = new WriteBatch(batchId, Instant.EPOCH,
                Map.of(player, new MinuteDelta(2, 0)), Map.of(), java.util.List.of());

        assertEquals(RecoveryApplyResult.APPLIED, runtime.repository().applyRecoveryBatch(snapshot));
        assertEquals(RecoveryApplyResult.ALREADY_APPLIED, runtime.repository().applyRecoveryBatch(snapshot));
        assertEquals(2L, runtime.repository().getLifetime(player).orElseThrow().activeMinutes);
    }

    @Test
    void disconnectReconnectAndReloadMakeOldCompletionStaleWithoutDuplicateOwnership() throws Exception {
        PlaytimeRuntime old = plugin.runtime();
        UUID uuid = UUID.randomUUID();
        old.repository().batchRecordMinutes(Map.of(uuid, new MinuteDelta(59, 0)), Instant.now());
        CountDownLatch readPaused = new CountDownLatch(1);
        CountDownLatch resumeRead = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        AtomicInteger announcements = new AtomicInteger();
        PlaytimeRuntime.setAnnouncementProbeForTesting(message -> announcements.incrementAndGet());
        PlaytimeRuntime.setTierReadProbeForTesting(stage -> {
            if (stage == PlaytimeRuntime.TierReadStage.AFTER_CUTOFF_BEFORE_SQL
                    && firstRead.compareAndSet(true, false)) {
                readPaused.countDown();
                await(resumeRead);
            }
        });
        PlayerMock player = new PlayerMock(MockBukkit.getMock(), "ReloadCutoff", uuid);
        MockBukkit.getMock().addPlayer(player);
        MockBukkit.getMock().getScheduler().performTicks(1);
        assertTrue(readPaused.await(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(old.acceptMinuteForTesting(player, 1, 0));
        old.handleQuitRecorded(uuid, Instant.now());
        old.handleJoinRecorded(player, Instant.now());

        Thread reload = new Thread(plugin::reloadPluginRuntime, "tier-reload");
        reload.start();
        resumeRead.countDown();
        reload.join();
        MockBukkit.getMock().getScheduler().performTicks(200);

        PlaytimeRuntime candidate = plugin.runtime();
        assertNotSame(old, candidate);
        assertEquals(org.enthusia.playtime.util.AsyncWriteQueue.EnqueueResult.CLOSED,
                old.writeQueue().enqueueMinute(uuid, 1, 0));
        assertEquals(60, candidate.repository().readLifetimeStrict(uuid).snapshot().activeMinutes);
        assertEquals(0, candidate.writeQueue().getAcceptedUncommittedTotals(uuid).activeMinutes);
        assertTrue(announcements.get() <= 1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
