package org.enthusia.playtime.service;

import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.util.AsyncWriteQueue;
import org.enthusia.playtime.util.PerformanceCounters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaytimeReadServiceBoundsTest {
    private static final String RANGE_ALL = "ALL";
    private static final String TOTAL_METRIC = "TOTAL";
    @Test
    void parameterizedLeaderboardCacheHasHardEvictionBound() {
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        when(plugin.isEnabled()).thenReturn(false);
        PlaytimeRepository repository = mock(PlaytimeRepository.class);
        PlaytimeReadService service = new PlaytimeReadService(plugin, repository,
                mock(AsyncWriteQueue.class), new PerformanceCounters(), 30, 100, 16);

        for (int page = 1; page <= 200; page++) {
            service.getLeaderboard(TOTAL_METRIC, RANGE_ALL, 10, page * 10);
        }

        assertTrue(service.leaderboardCacheSizeForTesting() <= 16);
        verifyNoInteractions(repository);
    }

    @Test
    void pagesOutsideConfiguredMaximumAreRejectedWithoutSql() {
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        when(plugin.isEnabled()).thenReturn(false);
        PlaytimeRepository repository = mock(PlaytimeRepository.class);
        PlaytimeReadService service = new PlaytimeReadService(plugin, repository,
                mock(AsyncWriteQueue.class), new PerformanceCounters(), 30, 25, 32);

        assertTrue(service.getLeaderboardPage(TOTAL_METRIC, RANGE_ALL, 26, 10).rows().isEmpty());
        assertTrue(service.getLeaderboardPage(TOTAL_METRIC, RANGE_ALL, Integer.MAX_VALUE, 10).rows().isEmpty());
        verifyNoInteractions(repository);
    }

    @Test
    void nextPageIsDerivedFromOneExtraRow() {
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        when(plugin.isEnabled()).thenReturn(false);
        PlaytimeReadService service = new PlaytimeReadService(plugin, mock(PlaytimeRepository.class),
                mock(AsyncWriteQueue.class), new PerformanceCounters(), 30, 1, 32);

        // The configured final page can never expose a Next action, even if stale data exists.
        assertFalse(service.getLeaderboardPage(TOTAL_METRIC, RANGE_ALL, 1, 10).hasNext());
    }
}
