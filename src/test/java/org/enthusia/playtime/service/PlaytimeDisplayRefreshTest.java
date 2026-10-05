package org.enthusia.playtime.service;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.data.model.PublicLeaderboardEntry;
import org.enthusia.playtime.data.model.RangeTotals;
import org.enthusia.playtime.util.AsyncWriteQueue;
import org.enthusia.playtime.util.PerformanceCounters;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlaytimeDisplayRefreshTest {
    @Test void minuteInvalidationRetainsLifetimeAndRangeWhileRefreshing() throws Exception {
        try (Fixture f = new Fixture()) {
            f.loadLifetime();
            when(f.repository.getRangeTotalsStrict(eq(f.uuid), any(), eq("ALL"))).thenReturn(new RangeTotals(5000, 0, 5000));
            f.service.getRangeTotals(f.uuid, "ALL");
            f.run();
            when(f.queue.getPendingTotals(f.uuid)).thenReturn(new RangeTotals(1, 0, 1));
            f.service.invalidatePlayer(f.uuid);
            assertEquals(5001, f.service.getLifetime(f.uuid).orElseThrow().activeMinutes);
            assertEquals(5001, f.service.getRangeTotals(f.uuid, "ALL").totalMinutes);
        }
    }

    @Test void joinInvalidationRetainsExistingPlayersAndLeaderboardRows() throws Exception {
        try (Fixture f = new Fixture()) {
            f.loadLifetime();
            PublicLeaderboardEntry row = new PublicLeaderboardEntry(1, f.uuid, "Player", "Player", 5000, 0, 5000, 5000, null, null, null);
            when(f.repository.getPublicLeaderboardStrict(eq("TOTAL"), eq("ALL"), any(), eq(10))).thenReturn(List.of(row));
            f.service.getPublicLeaderboard("TOTAL", "ALL", 10);
            f.run();
            f.service.invalidateAll();
            assertEquals(5000, f.service.getLifetime(f.uuid).orElseThrow().activeMinutes);
            assertEquals(List.of(row), f.service.getPublicLeaderboard("TOTAL", "ALL", 10));
        }
    }

    @Test void refreshInvalidatedDuringReadCannotPublishObsoleteResult() {
        try (Fixture f = new Fixture()) {
            f.loadLifetime();
            f.service.invalidatePlayer(f.uuid);
            f.service.getLifetime(f.uuid);
            when(f.repository.readLifetimeStrict(f.uuid)).thenAnswer(call -> {
                f.service.invalidatePlayer(f.uuid);
                return new PlaytimeRepository.LifetimeRead(PlaytimeRepository.LifetimeReadStatus.FOUND, new PlaytimeSnapshot(2, 0, 2));
            });
            f.run();
            assertEquals(5000, f.service.getLifetime(f.uuid).orElseThrow().activeMinutes);
            doReturn(new PlaytimeRepository.LifetimeRead(PlaytimeRepository.LifetimeReadStatus.FOUND, new PlaytimeSnapshot(6000, 0, 6000))).when(f.repository).readLifetimeStrict(f.uuid);
            f.run();
            assertEquals(6000, f.service.getLifetime(f.uuid).orElseThrow().activeMinutes);
        }
    }

    @Test void databaseFailureRetainsKnownLifetimeAndRetries() {
        try (Fixture f = new Fixture()) {
            f.loadLifetime();
            f.service.invalidatePlayer(f.uuid);
            when(f.repository.readLifetimeStrict(f.uuid)).thenReturn(new PlaytimeRepository.LifetimeRead(PlaytimeRepository.LifetimeReadStatus.FAILED, null));
            f.service.getLifetime(f.uuid);
            f.run();
            assertEquals(5000, f.service.getLifetime(f.uuid).orElseThrow().activeMinutes);
            when(f.repository.readLifetimeStrict(f.uuid)).thenReturn(new PlaytimeRepository.LifetimeRead(PlaytimeRepository.LifetimeReadStatus.FOUND, new PlaytimeSnapshot(5001, 0, 5001)));
            f.run();
            assertEquals(5001, f.service.getLifetime(f.uuid).orElseThrow().activeMinutes);
        }
    }

    @Test void leaderboardFailureRetainsRowsAndRetriesWithoutHidingSuccessfulEmptyResults() throws Exception {
        try (Fixture f = new Fixture()) {
            PublicLeaderboardEntry row = new PublicLeaderboardEntry(1, f.uuid, "Player", "Player", 5000, 0, 5000, 5000, null, null, null);
            when(f.repository.getPublicLeaderboardStrict(eq("TOTAL"), eq("ALL"), any(), eq(10))).thenReturn(List.of(row));
            f.service.getPublicLeaderboard("TOTAL", "ALL", 10);
            f.run();
            f.service.invalidatePlayer(f.uuid);
            when(f.repository.getPublicLeaderboardStrict(eq("TOTAL"), eq("ALL"), any(), eq(10))).thenThrow(new java.sql.SQLException("outage"));
            assertEquals(List.of(row), f.service.getPublicLeaderboard("TOTAL", "ALL", 10));
            f.run();
            assertEquals(List.of(row), f.service.getPublicLeaderboard("TOTAL", "ALL", 10));
            doReturn(List.of()).when(f.repository).getPublicLeaderboardStrict(eq("TOTAL"), eq("ALL"), any(), eq(10));
            f.run();
            assertTrue(f.service.getPublicLeaderboard("TOTAL", "ALL", 10).isEmpty());
        }
    }

    @Test void invalidationDuringLeaderboardReadDiscardsTheOldGeneration() throws Exception {
        try (Fixture f = new Fixture()) {
            PublicLeaderboardEntry row = new PublicLeaderboardEntry(1, f.uuid, "Player", "Player", 5000, 0, 5000, 5000, null, null, null);
            when(f.repository.getPublicLeaderboardStrict(eq("TOTAL"), eq("ALL"), any(), eq(10))).thenReturn(List.of(row));
            f.service.getPublicLeaderboard("TOTAL", "ALL", 10);
            f.run();
            f.service.invalidateAll();
            f.service.getPublicLeaderboard("TOTAL", "ALL", 10);
            when(f.repository.getPublicLeaderboardStrict(eq("TOTAL"), eq("ALL"), any(), eq(10))).thenAnswer(call -> {
                f.service.invalidateAll();
                return List.of();
            });
            f.run();
            assertEquals(List.of(row), f.service.getPublicLeaderboard("TOTAL", "ALL", 10));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final UUID uuid = UUID.randomUUID();
        final PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        final PlaytimeRepository repository = mock(PlaytimeRepository.class);
        final AsyncWriteQueue queue = mock(AsyncWriteQueue.class);
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final PlaytimeReadService service;
        Fixture() {
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            when(queue.getPendingTotals(any())).thenReturn(new RangeTotals(0, 0, 0));
            when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                tasks.add(call.getArgument(1));
                return null;
            });
            service = new PlaytimeReadService(plugin, repository, queue, new PerformanceCounters(), 30);
        }
        void loadLifetime() {
            when(repository.readLifetimeStrict(uuid)).thenReturn(new PlaytimeRepository.LifetimeRead(PlaytimeRepository.LifetimeReadStatus.FOUND, new PlaytimeSnapshot(5000, 0, 5000)));
            service.getLifetime(uuid);
            run();
        }
        void run() { tasks.remove().run(); }
        @Override public void close() { bukkit.close(); }
    }
}
