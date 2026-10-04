package org.enthusia.playtime.discord;

import github.scarsz.discordsrv.dependencies.jda.api.exceptions.ErrorResponseException;
import github.scarsz.discordsrv.dependencies.jda.api.requests.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.enthusia.playtime.PlayTimePlugin;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscordNumeralCoordinatorTest {
    @TempDir Path temporary;

    @Test void pendingSnapshotRetriesQuietlyEachSecondUntilItSucceeds() throws Exception {
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        NumeralRoleSyncService sync = mock(NumeralRoleSyncService.class);
        AtomicLong now = new AtomicLong();
        UUID uuid = UUID.randomUUID();
        when(sync.reconcile(uuid)).thenReturn(
                CompletableFuture.failedFuture(new CompletionException(new NumeralRoleSyncService.SnapshotPendingException())),
                CompletableFuture.failedFuture(new NumeralRoleSyncService.SnapshotPendingException()),
                CompletableFuture.completedFuture(null));
        DiscordNumeralCoordinator coordinator = new DiscordNumeralCoordinator(plugin, sync,
                new PendingUnlinkStore(temporary.resolve("pending.yml").toFile()), now::get);
        coordinator.request(uuid);
        coordinator.dispatchPlayers(now.get(), 8);
        now.set(999_999_999L);
        coordinator.dispatchPlayers(now.get(), 8);
        verify(sync, times(1)).reconcile(uuid);
        now.set(1_000_000_000L);
        coordinator.dispatchPlayers(now.get(), 8);
        verify(sync, times(2)).reconcile(uuid);
        now.set(2_000_000_000L);
        coordinator.dispatchPlayers(now.get(), 8);
        now.set(40_000_000_000L);
        coordinator.dispatchPlayers(now.get(), 8);
        verify(sync, times(3)).reconcile(uuid);
        verify(plugin, never()).getLogger();
    }

    @Test void databaseFailureRetainsThirtySecondRetryAndWarning() throws Exception {
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        Logger logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        NumeralRoleSyncService sync = mock(NumeralRoleSyncService.class);
        AtomicLong now = new AtomicLong();
        UUID uuid = UUID.randomUUID();
        IllegalStateException failure = new IllegalStateException("DB down");
        when(sync.reconcile(uuid)).thenReturn(CompletableFuture.failedFuture(failure),
                CompletableFuture.completedFuture(null));
        DiscordNumeralCoordinator coordinator = new DiscordNumeralCoordinator(plugin, sync,
                new PendingUnlinkStore(temporary.resolve("pending.yml").toFile()), now::get);
        coordinator.request(uuid);
        coordinator.dispatchPlayers(now.get(), 8);
        now.set(29_999_999_999L);
        coordinator.dispatchPlayers(now.get(), 8);
        verify(sync, times(1)).reconcile(uuid);
        now.set(30_000_000_000L);
        coordinator.dispatchPlayers(now.get(), 8);
        verify(sync, times(2)).reconcile(uuid);
        verify(logger).log(eq(Level.WARNING), contains("retrying"), same(failure));
    }

    @Test void failedOlderAttemptDoesNotDelayANewerJoinOrInitializationRequest() throws Exception {
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        NumeralRoleSyncService sync = mock(NumeralRoleSyncService.class);
        UUID uuid = UUID.randomUUID();
        CompletableFuture<Void> inflight = new CompletableFuture<>();
        when(sync.reconcile(uuid)).thenReturn(inflight, CompletableFuture.completedFuture(null));
        DiscordNumeralCoordinator coordinator = new DiscordNumeralCoordinator(plugin, sync,
                new PendingUnlinkStore(temporary.resolve("pending.yml").toFile()), () -> 0L);
        coordinator.request(uuid);
        coordinator.dispatchPlayers(0L, 8);
        coordinator.request(uuid);
        coordinator.dispatchPlayers(0L, 8);
        verify(sync, times(1)).reconcile(uuid);
        inflight.completeExceptionally(new NumeralRoleSyncService.SnapshotPendingException());
        coordinator.dispatchPlayers(0L, 8);
        verify(sync, times(2)).reconcile(uuid);
    }

    @Test void onlyUnknownMemberIsACompletedDiscordOperation() {
        ErrorResponseException absent = mock(ErrorResponseException.class);
        when(absent.getErrorResponse()).thenReturn(ErrorResponse.UNKNOWN_MEMBER);
        assertTrue(DiscordNumeralCoordinator.isMemberAbsent(new CompletionException(absent)));
        ErrorResponseException missingPermission = mock(ErrorResponseException.class);
        when(missingPermission.getErrorResponse()).thenReturn(ErrorResponse.MISSING_PERMISSIONS);
        assertFalse(DiscordNumeralCoordinator.isMemberAbsent(new CompletionException(missingPermission)));
        assertFalse(DiscordNumeralCoordinator.isMemberAbsent(new IllegalStateException("storage unavailable")));
    }
}
