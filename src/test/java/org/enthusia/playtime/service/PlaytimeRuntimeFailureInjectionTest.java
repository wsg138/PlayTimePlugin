package org.enthusia.playtime.service;

import org.bukkit.Bukkit;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.api.PlaytimeService;
import org.enthusia.playtime.data.DatabaseProvider;
import org.enthusia.playtime.skin.HeadCache;
import org.enthusia.playtime.util.AsyncWriteQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockbukkit.mockbukkit.MockBukkit;

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

    private static final class InjectedFailure extends RuntimeException {
        InjectedFailure(PlaytimeRuntime.CreationStage stage) { super(stage.name()); }
    }
}
