package org.enthusia.playtime.discord;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscordNumeralCoordinatorTest {
    private static final long ONE_SECOND_NANOS = 1_000_000_000L;
    private static final long THIRTY_SECONDS_NANOS = 30_000_000_000L;

    @Test
    void transientSnapshotRetriesAfterOneSecondEvenWhenWrapped() {
        Throwable failure = new CompletionException(
                new NumeralRoleSyncService.SnapshotPendingException("snapshot changed"));

        assertEquals(ONE_SECOND_NANOS, DiscordNumeralCoordinator.retryDelayNanos(failure));
    }

    @Test
    void ordinaryFailureKeepsThirtySecondRetry() {
        assertEquals(THIRTY_SECONDS_NANOS,
                DiscordNumeralCoordinator.retryDelayNanos(new IllegalStateException("database unavailable")));
    }
}
