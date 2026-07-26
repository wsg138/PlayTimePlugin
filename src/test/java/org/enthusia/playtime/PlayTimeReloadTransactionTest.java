package org.enthusia.playtime;

import org.bukkit.Bukkit;
import org.enthusia.playtime.api.PlaytimeService;
import org.enthusia.playtime.data.DatabaseProvider;
import org.enthusia.playtime.service.PlaytimeRuntime;
import org.enthusia.playtime.skin.HeadCache;
import org.enthusia.playtime.util.AsyncWriteQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.*;

class PlayTimeReloadTransactionTest {
    private PlayTimePlugin plugin;

    @BeforeEach void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.load(PlayTimePlugin.class);
    }

    @AfterEach void tearDown() {
        PlayTimePlugin.setReloadProbeForTesting(null);
        MockBukkit.unmock();
    }

    @ParameterizedTest
    @EnumSource(value = PlayTimePlugin.ReloadStage.class,
            names = {"CONFIG_LOADED", "OLD_PREPARED", "CANDIDATE_CREATED"})
    void precommitFailureRetainsExactlyOneOperationalOldRuntime(PlayTimePlugin.ReloadStage stage) {
        PlaytimeRuntime old = plugin.runtime();
        int providers = DatabaseProvider.openProviderCountForTesting();
        int executors = HeadCache.activeExecutorCountForTesting();
        int queues = AsyncWriteQueue.activeQueueCountForTesting();
        PlayTimePlugin.setReloadProbeForTesting(current -> {
            if (current == stage) throw new InjectedReloadFailure(stage);
        });
        assertFalse(plugin.reloadPluginRuntime());
        assertSame(old, plugin.runtime());
        assertSame(old.playtimeService(), Bukkit.getServicesManager().load(PlaytimeService.class));
        assertEquals(providers, DatabaseProvider.openProviderCountForTesting());
        assertEquals(executors, HeadCache.activeExecutorCountForTesting());
        assertEquals(queues, AsyncWriteQueue.activeQueueCountForTesting());
        assertEquals(AsyncWriteQueue.EnqueueResult.ACCEPTED,
                old.writeQueue().enqueueMinute(java.util.UUID.randomUUID(), 1, 0));
    }

    @ParameterizedTest
    @EnumSource(value = PlayTimePlugin.ReloadStage.class,
            names = {"OLD_COMMITTED", "CANDIDATE_PUBLISHED", "OLD_CLOSED", "PLACEHOLDER_REFRESH"})
    void postcommitFailureRetainsExactlyOneOperationalCandidate(PlayTimePlugin.ReloadStage stage) {
        PlaytimeRuntime old = plugin.runtime();
        PlayTimePlugin.setReloadProbeForTesting(current -> {
            if (current == stage) throw new InjectedReloadFailure(stage);
        });
        assertTrue(plugin.reloadPluginRuntime());
        PlaytimeRuntime candidate = plugin.runtime();
        assertNotSame(old, candidate);
        assertSame(candidate.playtimeService(), Bukkit.getServicesManager().load(PlaytimeService.class));
        assertEquals(1, DatabaseProvider.openProviderCountForTesting());
        assertEquals(1, HeadCache.activeExecutorCountForTesting());
        assertEquals(1, AsyncWriteQueue.activeQueueCountForTesting());
        assertEquals(AsyncWriteQueue.EnqueueResult.ACCEPTED,
                candidate.writeQueue().enqueueMinute(java.util.UUID.randomUUID(), 1, 0));
        assertEquals(AsyncWriteQueue.EnqueueResult.CLOSED,
                old.writeQueue().enqueueMinute(java.util.UUID.randomUUID(), 1, 0));
    }

    private static final class InjectedReloadFailure extends RuntimeException {
        InjectedReloadFailure(PlayTimePlugin.ReloadStage stage) { super(stage.name()); }
    }
}
