package org.enthusia.playtime.discord;

import net.enthusia.discord.platform.api.DiscordPlatformAvailability;
import net.enthusia.discord.platform.api.ManagedRoleClient;
import net.enthusia.discord.platform.api.ManagedRoleNamespace;
import net.enthusia.discord.platform.api.ManagedRolePlatform;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.service.PlaytimeRuntime;
import org.enthusia.playtime.util.NumeralTierCatalog;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EnthusiaNumeralShadowCoordinatorTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void rejectedPeriodicStartupScheduleDoesNotBlockPlayTimeStartup() {
        PlayTimePlugin plugin = plugin();
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("private scheduler details"));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            try (var coordinator = new EnthusiaNumeralShadowCoordinator(plugin, policy())) {
                assertDoesNotThrow(coordinator::start);
            }
        }
    }

    @Test
    void failedProviderDiscoveryNeverStrandsAnInFlightScan() {
        PlayTimePlugin plugin = plugin();
        ServicesManager services = mock(ServicesManager.class);
        when(services.load(ManagedRolePlatform.class)).thenThrow(
                new IllegalStateException("private provider details"));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            try (var coordinator = new EnthusiaNumeralShadowCoordinator(plugin, policy())) {
                assertDoesNotThrow(() -> coordinator.trigger(false));
                assertDoesNotThrow(() -> coordinator.trigger(false));
            }
            verify(services, times(2)).load(ManagedRolePlatform.class);
        }
    }

    @Test
    void classloaderFailureNeverStrandsOrEscapesIntoOrdinaryEvents() {
        PlayTimePlugin plugin = plugin();
        ServicesManager services = mock(ServicesManager.class);
        when(services.load(ManagedRolePlatform.class)).thenThrow(
                new NoClassDefFoundError("private provider details"));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            try (var coordinator = new EnthusiaNumeralShadowCoordinator(plugin, policy())) {
                assertDoesNotThrow(() -> coordinator.trigger(false));
                assertDoesNotThrow(() -> coordinator.trigger(false));
            }
            verify(services, times(2)).load(ManagedRolePlatform.class);
        }
    }

    @Test
    void failedAuthoritativeMetadataReadReleasesScanWithoutPublishing() {
        PlayTimePlugin plugin = plugin();
        PlaytimeRuntime runtime = mock(PlaytimeRuntime.class);
        when(plugin.runtime()).thenReturn(runtime);
        when(runtime.knownPlayerIds()).thenThrow(new IllegalStateException("db unavailable"));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            mockAvailableClient(bukkit);
            try (var coordinator = new EnthusiaNumeralShadowCoordinator(plugin, policy())) {
                assertDoesNotThrow(() -> coordinator.trigger(false));
                assertDoesNotThrow(() -> coordinator.trigger(false));
            }
            verify(runtime, times(2)).knownPlayerIds();
        }
    }

    @Test
    void rejectedAsyncScheduleReleasesScanAndAllowsNextAttempt() {
        PlayTimePlugin plugin = plugin();
        PlaytimeRuntime runtime = mock(PlaytimeRuntime.class);
        when(plugin.runtime()).thenReturn(runtime);
        when(runtime.knownPlayerIds()).thenReturn(Set.of(PLAYER));
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class)))
                .thenThrow(new IllegalStateException("scheduler unavailable"));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            mockAvailableClient(bukkit);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            try (var coordinator = new EnthusiaNumeralShadowCoordinator(plugin, policy())) {
                assertDoesNotThrow(() -> coordinator.trigger(false));
                assertDoesNotThrow(() -> coordinator.trigger(false));
            }
            verify(scheduler, times(2)).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        }
    }

    @Test
    void asyncPublisherFailureReleasesScanForNextAttempt() {
        PlayTimePlugin plugin = plugin();
        PlaytimeRuntime runtime = mock(PlaytimeRuntime.class);
        when(plugin.runtime()).thenReturn(runtime);
        when(runtime.knownPlayerIds()).thenReturn(Set.of(PLAYER));
        when(runtime.readAuthoritativeActiveMinutes(PLAYER)).thenReturn(90L);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(1)).run();
                    return mock(BukkitTask.class);
                });
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            ManagedRoleClient client = mockAvailableClient(bukkit);
            when(client.namespace()).thenReturn(new ManagedRoleNamespace("playtime-numerals"));
            when(client.reconcile(any())).thenReturn(
                    CompletableFuture.failedFuture(new IllegalStateException("private role identity")));
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            try (var coordinator = new EnthusiaNumeralShadowCoordinator(plugin, policy())) {
                assertDoesNotThrow(() -> coordinator.trigger(false));
                assertDoesNotThrow(() -> coordinator.trigger(false));
            }
            verify(client, times(2)).reconcile(any());
        }
    }

    @Test
    void failedFollowupSchedulingNeverEscapesToEventHandling() {
        PlayTimePlugin plugin = plugin();
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong()))
                .thenThrow(new IllegalStateException("private scheduler details"));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            try (var coordinator = new EnthusiaNumeralShadowCoordinator(plugin, policy())) {
                assertDoesNotThrow(coordinator::request);
                assertDoesNotThrow(coordinator::request);
            }
            verify(scheduler, times(2)).runTaskLater(eq(plugin), any(Runnable.class), anyLong());
        }
    }

    private static PlayTimePlugin plugin() {
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("EnthusiaNumeralShadowCoordinatorTest"));
        return plugin;
    }

    private static NumeralRolePolicy policy() {
        return new NumeralRolePolicy(new NumeralTierCatalog(List.of(
                new NumeralTierCatalog.Tier("I", 60L, "gray"))), Map.of("I", "10001"));
    }

    private static ManagedRoleClient mockAvailableClient(MockedStatic<Bukkit> bukkit) {
        ServicesManager services = mock(ServicesManager.class);
        ManagedRolePlatform platform = mock(ManagedRolePlatform.class);
        ManagedRoleClient client = mock(ManagedRoleClient.class);
        when(services.load(ManagedRolePlatform.class)).thenReturn(platform);
        when(platform.apiVersion()).thenReturn(ManagedRolePlatform.API_VERSION);
        when(platform.availability()).thenReturn(DiscordPlatformAvailability.AVAILABLE);
        when(platform.clientFor(any())).thenReturn(Optional.of(client));
        when(client.availability()).thenReturn(DiscordPlatformAvailability.AVAILABLE);
        bukkit.when(Bukkit::getServicesManager).thenReturn(services);
        return client;
    }
}
