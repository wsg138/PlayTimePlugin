package org.enthusia.playtime.discord;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.service.PlaytimeRuntime;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.stream.Collectors;

/** Bounded, retrying orchestration for numeral-role reconciliation over a provider-neutral port. */
public final class DiscordNumeralCoordinator implements AutoCloseable {
    private static final long RETRY_NANOS = 30_000_000_000L;
    private static final int MAX_REQUESTS_PER_SECOND = 8;
    private static final int SWEEP_INTERVAL_SECONDS = 300;

    private final PlayTimePlugin plugin;
    private final NumeralRoleProvider provider;
    private final NumeralRoleSyncService sync;
    private final PendingUnlinkStore unlinkStore;
    private final Map<UUID, PendingPlayer> pendingPlayers = new ConcurrentHashMap<>();
    private final Map<NumeralRoleAccountRef, Long> pendingUnlinks = new ConcurrentHashMap<>();
    private final Set<UUID> activePlayers = ConcurrentHashMap.newKeySet();
    private final Set<NumeralRoleAccountRef> activeUnlinks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong requestSequence = new AtomicLong();
    private final Object fileLock = new Object();
    private BukkitTask task;
    private int secondsSinceSweep = SWEEP_INTERVAL_SECONDS;

    public DiscordNumeralCoordinator(
            PlayTimePlugin plugin, NumeralRolePolicy policy, NumeralRoleProvider provider) throws IOException {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.unlinkStore = new PendingUnlinkStore(
                new File(plugin.getDataFolder(), "pending-discord-numeral-unlinks.yml"));
        this.sync = new NumeralRoleSyncService(policy, uuid -> {
            PlaytimeRuntime runtime = plugin.runtime();
            if (runtime == null) throw new IllegalStateException("Playtime runtime unavailable");
            return runtime.readAuthoritativeActiveMinutes(uuid);
        }, provider);
        for (String value : unlinkStore.load()) {
            pendingUnlinks.put(new NumeralRoleAccountRef(value), Long.MIN_VALUE);
        }
    }

    public void start() {
        provider.start(new NumeralRoleProvider.LinkListener() {
            @Override
            public void linked(UUID uuid) {
                request(uuid);
            }

            @Override
            public void unlinked(NumeralRoleAccountRef account) {
                requestUnlink(account);
            }
        });
        try {
            task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::drain, 20L, 20L);
        } catch (RuntimeException | Error failure) {
            provider.close();
            throw failure;
        }
    }

    public void request(UUID uuid) {
        if (!closed.get() && uuid != null) {
            pendingPlayers.put(uuid, new PendingPlayer(Long.MIN_VALUE, requestSequence.incrementAndGet()));
        }
    }

    private void requestUnlink(NumeralRoleAccountRef account) {
        synchronized (fileLock) {
            if (closed.get()) return;
            pendingUnlinks.put(account, Long.MIN_VALUE);
            persistUnlinks(false);
        }
    }

    private void drain() {
        if (closed.get()) return;
        sweepLinksWhenDue();
        long now = System.nanoTime();
        int dispatched = dispatchUnlinks(now);
        dispatchPlayers(now, MAX_REQUESTS_PER_SECOND - dispatched);
    }

    private void sweepLinksWhenDue() {
        secondsSinceSweep++;
        if (secondsSinceSweep < SWEEP_INTERVAL_SECONDS) return;
        try {
            provider.linkedMinecraftAccounts().forEach(this::request);
            secondsSinceSweep = 0;
        } catch (Exception exception) {
            secondsSinceSweep = SWEEP_INTERVAL_SECONDS - 30;
            plugin.getLogger().log(Level.WARNING,
                    "Could not enumerate linked numeral-role accounts; retrying.", exception);
        }
    }

    private int dispatchUnlinks(long now) {
        int dispatched = 0;
        for (Map.Entry<NumeralRoleAccountRef, Long> entry : pendingUnlinks.entrySet()) {
            if (dispatched >= MAX_REQUESTS_PER_SECOND) break;
            NumeralRoleAccountRef account = entry.getKey();
            if (entry.getValue() > now || !activeUnlinks.add(account)) continue;
            dispatched++;
            observeUnlink(account, sync.unlink(account));
        }
        return dispatched;
    }

    private void dispatchPlayers(long now, int limit) {
        int dispatched = 0;
        for (Map.Entry<UUID, PendingPlayer> entry : pendingPlayers.entrySet()) {
            if (dispatched >= limit) break;
            UUID uuid = entry.getKey();
            PendingPlayer pending = entry.getValue();
            if (pending.dueNanos() > now || !activePlayers.add(uuid)) continue;
            dispatched++;
            observePlayer(uuid, pending, sync.reconcile(uuid));
        }
    }

    private void observePlayer(UUID uuid, PendingPlayer pending, CompletableFuture<Void> future) {
        future.whenComplete((ignored, error) -> {
            if (error == null) {
                pendingPlayers.remove(uuid, pending);
            } else {
                pendingPlayers.replace(uuid, pending,
                        new PendingPlayer(System.nanoTime() + RETRY_NANOS, pending.version()));
                plugin.getLogger().log(Level.WARNING,
                        "Numeral role sync failed for " + uuid + "; retrying.", error);
            }
            activePlayers.remove(uuid);
        });
    }

    private void observeUnlink(NumeralRoleAccountRef account, CompletableFuture<Void> future) {
        future.whenComplete((ignored, error) -> {
            if (error == null) {
                pendingUnlinks.remove(account);
                persistUnlinks(false);
            } else {
                pendingUnlinks.put(account, System.nanoTime() + RETRY_NANOS);
                plugin.getLogger().log(Level.WARNING,
                        "Numeral role unlink reconciliation failed; retrying.", error);
            }
            activeUnlinks.remove(account);
        });
    }

    private void persistUnlinks(boolean closing) {
        synchronized (fileLock) {
            if (closed.get() && !closing) return;
            try {
                Set<String> values = pendingUnlinks.keySet().stream()
                        .map(NumeralRoleAccountRef::value)
                        .collect(Collectors.toUnmodifiableSet());
                unlinkStore.save(values);
            } catch (IOException exception) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not persist numeral role unlink reconciliation queue.", exception);
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (task != null) task.cancel();
        try {
            provider.close();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to close numeral role provider.", exception);
        }
        persistUnlinks(true);
    }

    private record PendingPlayer(long dueNanos, long version) { }
}
