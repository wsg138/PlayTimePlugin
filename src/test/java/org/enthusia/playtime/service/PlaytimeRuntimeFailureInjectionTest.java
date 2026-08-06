package org.enthusia.playtime.service;

import org.bukkit.Bukkit;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.api.PlaytimeService;
import org.enthusia.playtime.activity.ActivityState;
import org.enthusia.playtime.data.DatabaseProvider;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.skin.HeadCache;
import org.enthusia.playtime.util.AsyncWriteQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlaytimeRuntimeFailureInjectionTest {
    private PlayTimePlugin plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.load(PlayTimePlugin.class);
    }

    @AfterEach
    void tearDown() {
        PlaytimeRuntime.setCreationProbeForTesting(null);
        MockBukkit.unmock();
    }

    @ParameterizedTest
    @EnumSource(PlaytimeRuntime.CreationStage.class)
    void everyPrecommitCreationFailureCleansCandidateAndRestoresOldRuntime(
            PlaytimeRuntime.CreationStage failureStage) {
        PlaytimeRuntime old = plugin.runtime();
        int tasksBefore = Bukkit.getScheduler().getPendingTasks().size();
        int providersBefore = DatabaseProvider.openProviderCountForTesting();
        int executorsBefore = HeadCache.activeExecutorCountForTesting();
        int queuesBefore = AsyncWriteQueue.activeQueueCountForTesting();
        PlaytimeService serviceBefore = Bukkit.getServicesManager().load(PlaytimeService.class);
        PlaytimeRuntime.setCreationProbeForTesting(stage -> {
            if (stage == failureStage) throw new InjectedFailure(stage);
        });

        assertFalse(plugin.reloadPluginRuntime(), failureStage.name());
        assertSame(old, plugin.runtime(), failureStage.name());
        assertSame(serviceBefore, Bukkit.getServicesManager().load(PlaytimeService.class));
        assertSame(old.playtimeService(), Bukkit.getServicesManager().load(PlaytimeService.class));
        assertEquals(tasksBefore, Bukkit.getScheduler().getPendingTasks().size(), failureStage.name());
        assertEquals(providersBefore, DatabaseProvider.openProviderCountForTesting(), failureStage.name());
        assertEquals(executorsBefore, HeadCache.activeExecutorCountForTesting(), failureStage.name());
        assertEquals(queuesBefore, AsyncWriteQueue.activeQueueCountForTesting(), failureStage.name());
        assertEquals(org.enthusia.playtime.util.AsyncWriteQueue.EnqueueResult.ACCEPTED,
                old.writeQueue().enqueueMinute(java.util.UUID.randomUUID(), 1, 0));
        assertTrue(plugin.isEnabled());
    }

    @org.junit.jupiter.api.Test
    void failedCandidateCleanupDoesNotDrainTransferredAccrual() throws Exception {
        PlaytimeRuntime old = plugin.runtime();
        PlaytimeRuntime.HandoffPreparation preparation = old.prepareRuntimeHandoff();
        assertEquals(AsyncWriteQueue.TransitionResult.SUCCESS, preparation.result());

        UUID uuid = UUID.randomUUID();
        try {
            PlayerMock player = new PlayerMock(MockBukkit.getMock(), "RollbackPlayer", uuid);
            MockBukkit.getMock().addPlayer(player);
            assertEquals(PlaytimeRepository.LifetimeReadStatus.NOT_FOUND,
                    old.repository().readLifetimeStrict(uuid).status());

            long partialNanos = PlaytimeAccrualTracker.NANOS_PER_MINUTE - 1L;
            Instant intervalStart = Instant.now().minusSeconds(59L);
            PlaytimeAccrualTracker.Snapshot transferred = new PlaytimeAccrualTracker.Snapshot(
                    true, 0L, intervalStart, ActivityState.ACTIVE, partialNanos,
                    List.of(new PlaytimeAccrualTracker.SegmentSnapshot(
                            ActivityState.ACTIVE, partialNanos, intervalStart)),
                    0, 1L, false);
            PlaytimeRuntime.RuntimeState state = new PlaytimeRuntime.RuntimeState(
                    Map.of(), Map.of(), Map.of(), Map.of(uuid, transferred), Map.of(uuid, 1L));
            PlaytimeRuntime.setCreationProbeForTesting(stage -> {
                if (stage == PlaytimeRuntime.CreationStage.QUEUE_STARTED) {
                    throw new InjectedFailure(stage);
                }
            });

            assertThrows(InjectedFailure.class,
                    () -> PlaytimeRuntime.create(plugin, plugin.getRuntimeConfig(), state));
            assertSame(old, plugin.runtime());
            assertEquals(PlaytimeRepository.LifetimeReadStatus.NOT_FOUND,
                    old.repository().readLifetimeStrict(uuid).status());
        } finally {
            old.abortRuntimeHandoff();
        }
    }

    private static final class InjectedFailure extends RuntimeException {
        InjectedFailure(PlaytimeRuntime.CreationStage stage) { super(stage.name()); }
    }
}
