package org.enthusia.playtime.discord;

import net.enthusia.discord.platform.api.DiscordPlatformAvailability;
import net.enthusia.discord.platform.api.ManagedRoleClient;
import net.enthusia.discord.platform.api.ManagedRolePlatform;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.service.PlaytimeRuntime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Migration-only scheduler for provider-neutral numeral-role parity claims.
 *
 * DiscordSRV remains authoritative for role mutations. This coordinator only resolves the existing
 * role names and publishes complete Minecraft UUID snapshots to EnthusiaStaff.
 */
public final class EnthusiaNumeralShadowCoordinator implements AutoCloseable {
    private static final long INITIAL_DELAY_TICKS = 20L * 15L;
    private static final long PERIOD_TICKS = 20L * 60L * 5L;

    private final PlayTimePlugin plugin;
    private final NumeralRolePolicy policy;
    private final DiscordSrvNumeralRoleProvider legacyProvider;
    private final EnthusiaNumeralShadowPublisher publisher;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private BukkitTask task;

    public EnthusiaNumeralShadowCoordinator(
            PlayTimePlugin plugin,
            NumeralRolePolicy policy,
            DiscordSrvNumeralRoleProvider legacyProvider
    ) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
        this.legacyProvider = java.util.Objects.requireNonNull(legacyProvider, "legacyProvider");
        this.publisher = new EnthusiaNumeralShadowPublisher(policy);
    }

    public void start() {
        if (closed.get() || task != null) {
            throw new IllegalStateException("Enthusia numeral shadow coordinator cannot be started");
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::trigger, INITIAL_DELAY_TICKS, PERIOD_TICKS);
        plugin.getLogger().info(
                "Enthusia numeral-role shadow publication enabled; DiscordSRV remains authoritative for mutations.");
    }

    private void trigger() {
        if (closed.get() || !inFlight.compareAndSet(false, true)) {
            return;
        }

        ManagedRoleClient client = client();
        PlaytimeRuntime runtime = plugin.runtime();
        if (client == null || runtime == null) {
            inFlight.set(false);
            return;
        }

        final Map<String, String> roleNames;
        final Set<UUID> players;
        try {
            roleNames = roleNames();
            players = runtime.knownPlayerIds();
        } catch (RuntimeException exception) {
            inFlight.set(false);
            logFailure("metadata", exception);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> publish(client, runtime, roleNames, players));
    }

    private void publish(
            ManagedRoleClient client,
            PlaytimeRuntime runtime,
            Map<String, String> roleNames,
            Set<UUID> players
    ) {
        try {
            publisher.publish(client, roleNames, players, runtime::readAuthoritativeActiveMinutes)
                    .whenComplete((summary, error) -> {
                        if (error != null) {
                            logFailure("publish", unwrap(error));
                        } else if (plugin.getLogger().isLoggable(Level.FINE)) {
                            plugin.getLogger().fine(
                                    "Enthusia numeral-role shadow published " + summary.claimsPublished()
                                            + " claim(s) for " + summary.playersEvaluated() + " known player(s).");
                        }
                        inFlight.set(false);
                    });
        } catch (RuntimeException exception) {
            inFlight.set(false);
            logFailure("snapshot", exception);
        }
    }

    private ManagedRoleClient client() {
        ManagedRolePlatform platform = Bukkit.getServicesManager().load(ManagedRolePlatform.class);
        if (platform == null
                || platform.apiVersion() != ManagedRolePlatform.API_VERSION
                || platform.availability() == DiscordPlatformAvailability.UNAVAILABLE) {
            return null;
        }
        Optional<ManagedRoleClient> client = platform.clientFor(EnthusiaNumeralShadowPublisher.namespace());
        return client.filter(value -> value.availability() != DiscordPlatformAvailability.UNAVAILABLE).orElse(null);
    }

    private Map<String, String> roleNames() {
        Map<String, String> names = new LinkedHashMap<>();
        policy.roleIdsByTier().forEach((tier, roleId) -> names.put(tier, legacyProvider.roleName(roleId)));
        return Map.copyOf(names);
    }

    private void logFailure(String stage, Throwable error) {
        plugin.getLogger().log(
                Level.WARNING,
                "Enthusia numeral-role shadow " + stage + " failed; no cutover decision should use this pass.",
                error
        );
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof java.util.concurrent.CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        BukkitTask current = task;
        task = null;
        if (current != null) {
            current.cancel();
        }
    }
}
