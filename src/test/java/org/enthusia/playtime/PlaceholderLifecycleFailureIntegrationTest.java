package org.enthusia.playtime;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
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

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PlaceholderLifecycleFailureIntegrationTest {
    enum Failure { UNREGISTER, REGISTER }
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
    @EnumSource(Failure.class)
    void precommitPlaceholderFailurePreservesExactlyOneOperationalOldRuntime(Failure failure) {
        PlaytimeRuntime old = plugin.runtime();
        FailingPlaceholderLifecycle lifecycle = new FailingPlaceholderLifecycle(failure);
        plugin.setPlaceholderLifecycleForTesting(lifecycle);
        int listeners = HandlerList.getRegisteredListeners(plugin).size();
        int tasks = ownedTaskCount();
        PlayTimePlugin.setReloadProbeForTesting(stage -> {
            if (stage == PlayTimePlugin.ReloadStage.CANDIDATE_CREATED) {
                plugin.refreshPlaceholderExpansionForTesting();
            }
        });

        assertFalse(plugin.reloadPluginRuntime());
        assertSame(old, plugin.runtime());
        assertSame(old.playtimeService(), Bukkit.getServicesManager().load(PlaytimeService.class));
        assertEquals(1, DatabaseProvider.openProviderCountForTesting());
        assertEquals(1, HeadCache.activeExecutorCountForTesting());
        assertEquals(1, AsyncWriteQueue.activeQueueCountForTesting());
        assertEquals(listeners, HandlerList.getRegisteredListeners(plugin).size());
        assertEquals(tasks, ownedTaskCount());
        assertEquals(AsyncWriteQueue.EnqueueResult.ACCEPTED,
                old.writeQueue().enqueueMinute(java.util.UUID.randomUUID(), 1, 0));
    }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void postcommitPlaceholderFailurePreservesExactlyOneOperationalCandidate(Failure failure) {
        PlaytimeRuntime old = plugin.runtime();
        int listeners = HandlerList.getRegisteredListeners(plugin).size();
        int tasks = ownedTaskCount();
        plugin.setPlaceholderLifecycleForTesting(new FailingPlaceholderLifecycle(failure));

        assertTrue(plugin.reloadPluginRuntime());
        PlaytimeRuntime candidate = plugin.runtime();
        assertNotSame(old, candidate);
        assertSame(candidate.playtimeService(), Bukkit.getServicesManager().load(PlaytimeService.class));
        assertEquals(1, DatabaseProvider.openProviderCountForTesting());
        assertEquals(1, HeadCache.activeExecutorCountForTesting());
        assertEquals(1, AsyncWriteQueue.activeQueueCountForTesting());
        assertEquals(listeners, HandlerList.getRegisteredListeners(plugin).size());
        assertEquals(tasks, ownedTaskCount());
        assertEquals(AsyncWriteQueue.EnqueueResult.CLOSED,
                old.writeQueue().enqueueMinute(java.util.UUID.randomUUID(), 1, 0));
        assertEquals(AsyncWriteQueue.EnqueueResult.ACCEPTED,
                candidate.writeQueue().enqueueMinute(java.util.UUID.randomUUID(), 1, 0));
    }

    private int ownedTaskCount() {
        return (int) Bukkit.getScheduler().getPendingTasks().stream()
                .filter(task -> task.getOwner() == plugin)
                .count();
    }

    private static final class FailingPlaceholderLifecycle implements PlayTimePlugin.PlaceholderLifecycle {
        private final Failure failure;
        private final AtomicBoolean registered = new AtomicBoolean(true);

        private FailingPlaceholderLifecycle(Failure failure) {
            this.failure = failure;
        }

        @Override public boolean available() {
            return true;
        }

        @Override public boolean registered() {
            return registered.get();
        }

        @Override public void unregister() {
            if (failure == Failure.UNREGISTER) throw new InjectedPlaceholderFailure(failure);
            registered.set(false);
        }

        @Override public void register() {
            if (failure == Failure.REGISTER) throw new InjectedPlaceholderFailure(failure);
            registered.set(true);
        }
    }

    private static final class InjectedPlaceholderFailure extends RuntimeException {
        private InjectedPlaceholderFailure(Failure failure) {
            super(failure.name());
        }
    }
}
