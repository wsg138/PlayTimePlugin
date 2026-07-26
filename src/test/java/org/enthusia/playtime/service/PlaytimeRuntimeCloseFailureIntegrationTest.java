package org.enthusia.playtime.service;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
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

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

class PlaytimeRuntimeCloseFailureIntegrationTest {
    private PlayTimePlugin plugin;

    @BeforeEach void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.load(PlayTimePlugin.class);
    }

    @AfterEach void tearDown() {
        PlaytimeRuntime.setCloseProbeForTesting(null);
        MockBukkit.unmock();
    }

    @ParameterizedTest
    @EnumSource(PlaytimeRuntime.CloseStage.class)
    void everyOldCloseFailureContinuesCleanupAndRetainsCommittedCandidate(
            PlaytimeRuntime.CloseStage failedStage) {
        PlaytimeRuntime old = plugin.runtime();
        int listeners = HandlerList.getRegisteredListeners(plugin).size();
        int tasks = ownedTaskCount();
        AtomicInteger warnings = new AtomicInteger();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()
                        && record.getMessage().contains("cleanup stage")) warnings.incrementAndGet();
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        plugin.getLogger().addHandler(handler);
        PlaytimeRuntime.setCloseProbeForTesting(stage -> {
            if (stage == failedStage) throw new InjectedCloseFailure(stage);
        });

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
                old.writeQueue().enqueueMinute(UUID.randomUUID(), 1, 0));
        assertEquals(AsyncWriteQueue.EnqueueResult.ACCEPTED,
                candidate.writeQueue().enqueueMinute(UUID.randomUUID(), 1, 0));
        assertEquals(1, warnings.get());
        plugin.getLogger().removeHandler(handler);
        PlaytimeRuntime.setCloseProbeForTesting(null);
    }

    private int ownedTaskCount() {
        return (int) Bukkit.getScheduler().getPendingTasks().stream()
                .filter(task -> task.getOwner() == plugin)
                .count();
    }

    private static final class InjectedCloseFailure extends RuntimeException {
        InjectedCloseFailure(PlaytimeRuntime.CloseStage stage) {
            super(stage.name());
        }
    }
}
