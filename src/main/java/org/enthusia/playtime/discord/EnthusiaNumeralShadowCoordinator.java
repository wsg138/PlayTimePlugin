package org.enthusia.playtime.discord;

import net.enthusia.discord.platform.api.DiscordPlatformAvailability;
import net.enthusia.discord.platform.api.ManagedRoleClient;
import net.enthusia.discord.platform.api.ManagedRolePlatform;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.service.PlaytimeRuntime;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Migration-only scheduler for provider-neutral numeral-role parity claims.
 *
 * DiscordSRV remains authoritative for role mutations. This coordinator publishes complete
 * Minecraft UUID snapshots plus the already-configured legacy role IDs to EnthusiaStaff.
 */
public final class EnthusiaNumeralShadowCoordinator implements AutoCloseable {
    private static final long INITIAL_DELAY_TICKS = 20L * 15L;
    private static final long PERIOD_TICKS = 20L * 60L * 5L;

    private final PlayTimePlugin plugin;
    private final NumeralRolePolicy policy;
    private final EnthusiaNumeralShadowPublisher publisher;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicBoolean requestQueued = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Optional<BukkitTask> task = Optional.empty();

    public EnthusiaNumeralShadowCoordinator(
            PlayTimePlugin plugin,
            NumeralRolePolicy policy
    ) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
        this.publisher = new EnthusiaNumeralShadowPublisher(policy);
    }

    public void start() {
        if (closed.get() || task.isPresent()) {
            throw new IllegalStateException("Enthusia numeral shadow coordinator cannot be started");
        }
        task = Optional.of(Bukkit.getScheduler().runTaskTimer(plugin, this::trigger, INITIAL_DELAY_TICKS, PERIOD_TICKS));
        plugin.getLogger().info(
                "Enthusia numeral-role shadow publication enabled; DiscordSRV remains authoritative for mutations.");
    }

    public void request() {
        if (closed.get() || !requestQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                requestQueued.set(false);
                trigger();
            });
        } catch (RuntimeException | LinkageError failure) {
            requestQueued.set(false);
            throw failure;
        }
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

        final Set<UUID> players;
        try {
            players = runtime.knownPlayerIds();
        } catch (RuntimeException exception) {
            inFlight.set(false);
            logFailure("metadata", exception);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> publish(client, runtime, players));
    }

    private void publish(
            ManagedRoleClient client,
            PlaytimeRuntime runtime,
            Set<UUID> players
    ) {
        try {
            publisher.publish(
                    client,
                    policy.roleIdsByTier(),
                    players,
                    runtime::readAuthoritativeActiveMinutes)
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
        Optional<BukkitTask> current = task;
        task = Optional.empty();
        current.ifPresent(BukkitTask::cancel);
    }
}
