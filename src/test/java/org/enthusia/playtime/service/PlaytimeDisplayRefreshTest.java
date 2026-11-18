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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlaytimeDisplayRefreshTest {
    private static final String ALL_TIME = "ALL";
    private static final String TOTAL_METRIC = "TOTAL";
    private static final String PLAYER_NAME = "Player";

    @Test void minuteInvalidationRetainsLifetimeAndRangeWhileRefreshing() throws Exception {
        try (Fixture f = new Fixture()) {
            f.loadLifetime();
            when(f.repository.getRangeTotalsStrict(eq(f.uuid), any(), eq(ALL_TIME))).thenReturn(new RangeTotals(5000, 0, 5000));
            f.service.getRangeTotals(f.uuid, ALL_TIME);
            f.run();
            when(f.queue.getPendingTotals(f.uuid)).thenReturn(new RangeTotals(1, 0, 1));
            f.service.invalidatePlayer(f.uuid);
            assertEquals(5000, f.service.getLifetime(f.uuid).orElseThrow().activeMinutes);
            assertEquals(5000, f.service.getRangeTotals(f.uuid, ALL_TIME).totalMinutes);
            f.run();
            f.run();
            assertEquals(5001, f.service.getLifetime(f.uuid).orElseThrow().activeMinutes);
            assertEquals(5001, f.service.getRangeTotals(f.uuid, ALL_TIME).totalMinutes);
        }
    }

    @Test void flushDoesNotDropMinutesFromWarmLifetimeOrRange() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.queue.getPendingTotals(f.uuid)).thenReturn(new RangeTotals(1, 0, 1));
            f.loadLifetime();
            when(f.repository.getRangeTotalsStrict(eq(f.uuid), any(), eq(ALL_TIME)))
                    .thenReturn(new RangeTotals(5000, 0, 5000));
            f.service.getRangeTotals(f.uuid, ALL_TIME);
            f.run();
            assertEquals(5001, f.service.getLifetime(f.uuid).orElseThrow().activeMinutes);
            assertEquals(5001, f.service.getRangeTotals(f.uuid, ALL_TIME).totalMinutes);

            when(f.queue.getPendingTotals(f.uuid)).thenReturn(new RangeTotals(0, 0, 0));
            assertEquals(5001, f.service.getLifetime(f.uuid).orElseThrow().activeMinutes);
            assertEquals(5001, f.service.getRangeTotals(f.uuid, ALL_TIME).totalMinutes);
        }
    }

    @Test void joinInvalidationRetainsExistingPlayersAndLeaderboardRows() throws Exception {
        try (Fixture f = new Fixture()) {
            f.loadLifetime();
            PublicLeaderboardEntry row = new PublicLeaderboardEntry(1, f.uuid, PLAYER_NAME, PLAYER_NAME, 5000, 0, 5000, 5000, null, null, null);
            when(f.repository.getPublicLeaderboardStrict(eq(TOTAL_METRIC), eq(ALL_TIME), any(), eq(10))).thenReturn(List.of(row));
            f.service.getPublicLeaderboard(TOTAL_METRIC, ALL_TIME, 10);
            f.run();
            f.service.invalidateAll();
            assertEquals(5000, f.service.getLifetime(f.uuid).orElseThrow().activeMinutes);
            assertEquals(List.of(row), f.service.getPublicLeaderboard(TOTAL_METRIC, ALL_TIME, 10));
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
            assertTrue(f.service.isLifetimeFailed(f.uuid));
            assertTrue(f.tasks.isEmpty());
            when(f.repository.readLifetimeStrict(f.uuid)).thenReturn(new PlaytimeRepository.LifetimeRead(PlaytimeRepository.LifetimeReadStatus.FOUND, new PlaytimeSnapshot(5001, 0, 5001)));
            f.advanceRetryClock();
            f.service.getLifetime(f.uuid);
            f.run();
            assertEquals(5001, f.service.getLifetime(f.uuid).orElseThrow().activeMinutes);
            assertFalse(f.service.isLifetimeFailed(f.uuid));
        }
    }

    @Test void leaderboardFailureRetainsRowsAndRetriesWithoutHidingSuccessfulEmptyResults() throws Exception {
        try (Fixture f = new Fixture()) {
            PublicLeaderboardEntry row = new PublicLeaderboardEntry(1, f.uuid, PLAYER_NAME, PLAYER_NAME, 5000, 0, 5000, 5000, null, null, null);
            when(f.repository.getPublicLeaderboardStrict(eq(TOTAL_METRIC), eq(ALL_TIME), any(), eq(10))).thenReturn(List.of(row));
            f.service.getPublicLeaderboard(TOTAL_METRIC, ALL_TIME, 10);
            f.run();
            f.service.invalidatePlayer(f.uuid);
            when(f.repository.getPublicLeaderboardStrict(eq(TOTAL_METRIC), eq(ALL_TIME), any(), eq(10))).thenThrow(new java.sql.SQLException("outage"));
            assertEquals(List.of(row), f.service.getPublicLeaderboard(TOTAL_METRIC, ALL_TIME, 10));
            f.run();
            assertEquals(List.of(row), f.service.getPublicLeaderboard(TOTAL_METRIC, ALL_TIME, 10));
            assertTrue(f.tasks.isEmpty(), "a failed query must cool down before another poll retries");
            f.service.invalidateAll();
            f.service.getPublicLeaderboard(TOTAL_METRIC, ALL_TIME, 10);
            assertTrue(f.tasks.isEmpty(), "invalidation must not bypass the cooldown");
            doReturn(List.of()).when(f.repository).getPublicLeaderboardStrict(eq(TOTAL_METRIC), eq(ALL_TIME), any(), eq(10));
            f.advanceRetryClock();
            f.service.getPublicLeaderboard(TOTAL_METRIC, ALL_TIME, 10);
            f.run();
            assertTrue(f.service.getPublicLeaderboard(TOTAL_METRIC, ALL_TIME, 10).isEmpty());
        }
    }

    @Test void invalidationDuringLeaderboardReadDiscardsTheOldGeneration() throws Exception {
        try (Fixture f = new Fixture()) {
            PublicLeaderboardEntry row = new PublicLeaderboardEntry(1, f.uuid, PLAYER_NAME, PLAYER_NAME, 5000, 0, 5000, 5000, null, null, null);
            when(f.repository.getPublicLeaderboardStrict(eq(TOTAL_METRIC), eq(ALL_TIME), any(), eq(10))).thenReturn(List.of(row));
            f.service.getPublicLeaderboard(TOTAL_METRIC, ALL_TIME, 10);
            f.run();
            f.service.invalidateAll();
            f.service.getPublicLeaderboard(TOTAL_METRIC, ALL_TIME, 10);
            when(f.repository.getPublicLeaderboardStrict(eq(TOTAL_METRIC), eq(ALL_TIME), any(), eq(10))).thenAnswer(call -> {
                f.service.invalidateAll();
                return List.of();
            });
            f.run();
            assertEquals(List.of(row), f.service.getPublicLeaderboard(TOTAL_METRIC, ALL_TIME, 10));
        }
    }

    @Test void coldLookupCanPopulateAFullCacheOfLoadedPlayers() {
        try (Fixture f = new Fixture()) {
            var service = new PlaytimeReadService(f.plugin, f.repository, f.queue, new PerformanceCounters(), 30, 100, 16);
            var stored = new PlaytimeRepository.LifetimeRead(PlaytimeRepository.LifetimeReadStatus.FOUND,
                    new PlaytimeSnapshot(5000, 0, 5000));
            when(f.repository.readLifetimeStrict(any())).thenReturn(stored);
            for (int i = 0; i < 16; i++) {
                service.getLifetime(UUID.randomUUID());
                f.run();
            }
            service.getLifetime(f.uuid);
            f.run();
            assertEquals(5000, service.getLifetime(f.uuid).orElseThrow().activeMinutes);
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
        long nowNanos;
        Fixture() {
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            when(queue.getPendingTotals(any())).thenReturn(new RangeTotals(0, 0, 0));
            when(queue.readPlayerDisplaySnapshot(any(), any())).thenAnswer(call -> {
                UUID player = call.getArgument(0);
                Function<RangeTotals, ?> query = call.getArgument(1);
                return Optional.of(query.apply(queue.getPendingTotals(player)));
            });
            when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                tasks.add(call.getArgument(1));
                return null;
            });
            service = new PlaytimeReadService(plugin, repository, queue, new PerformanceCounters(), 30,
                    100, 512, () -> nowNanos);
        }
        void advanceRetryClock() { nowNanos += TimeUnit.SECONDS.toNanos(5); }
        void loadLifetime() {
            when(repository.readLifetimeStrict(uuid)).thenReturn(new PlaytimeRepository.LifetimeRead(PlaytimeRepository.LifetimeReadStatus.FOUND, new PlaytimeSnapshot(5000, 0, 5000)));
            service.getLifetime(uuid);
            run();
        }
        void run() { tasks.remove().run(); }
        @Override public void close() { bukkit.close(); }
    }
}
