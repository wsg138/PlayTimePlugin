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
import java.util.logging.Level;

/** Bounded, retrying orchestration for numeral-role reconciliation over a provider-neutral port. */
public final class DiscordNumeralCoordinator implements AutoCloseable {
    private static final long RETRY_NANOS = 30_000_000_000L;
    private static final int MAX_REQUESTS_PER_SECOND = 8;
    private static final int SWEEP_INTERVAL_SECONDS = 300;

    private final PlayTimePlugin plugin;
    private final NumeralRoleProvider provider;
    private final NumeralRoleSyncService sync;
    private final PendingUnlinkStore unlinkStore;
    private final PendingReconciliationQueue<UUID> pendingPlayers = new PendingReconciliationQueue<>();
    private final PendingReconciliationQueue<NumeralRoleAccountRef> pendingUnlinks = new PendingReconciliationQueue<>();
    private final Set<UUID> activePlayers = ConcurrentHashMap.newKeySet();
    private final Set<NumeralRoleAccountRef> activeUnlinks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
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
        for (NumeralRoleAccountRef account : unlinkStore.load()) {
            pendingUnlinks.request(account);
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
        } catch (RuntimeException | LinkageError failure) {
            try {
                provider.close();
            } catch (RuntimeException | LinkageError closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public void request(UUID uuid) {
        if (!closed.get() && uuid != null) {
            pendingPlayers.request(uuid);
        }
    }

    private void requestUnlink(NumeralRoleAccountRef account) {
        synchronized (fileLock) {
            if (closed.get()) return;
            pendingUnlinks.request(account);
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
            if (!provider.linksAvailable()) return;
            provider.linkedMinecraftAccounts().forEach(this::request);
            secondsSinceSweep = 0;
        } catch (RuntimeException | LinkageError exception) {
            secondsSinceSweep = SWEEP_INTERVAL_SECONDS - 30;
            plugin.getLogger().log(Level.WARNING,
                    "Could not enumerate linked numeral-role accounts; retrying.", exception);
        }
    }

    private int dispatchUnlinks(long now) {
        int dispatched = 0;
        for (Map.Entry<NumeralRoleAccountRef, PendingReconciliationQueue.Pending> entry
                : pendingUnlinks.snapshot().entrySet()) {
            if (dispatched >= MAX_REQUESTS_PER_SECOND) break;
            NumeralRoleAccountRef account = entry.getKey();
            PendingReconciliationQueue.Pending pending = entry.getValue();
            if (pending.dueNanos() > now || !activeUnlinks.add(account)) continue;
            dispatched++;
            observeUnlink(account, pending, sync.unlink(account));
        }
        return dispatched;
    }

    private void dispatchPlayers(long now, int limit) {
        int dispatched = 0;
        for (Map.Entry<UUID, PendingReconciliationQueue.Pending> entry
                : pendingPlayers.snapshot().entrySet()) {
            if (dispatched >= limit) break;
            UUID uuid = entry.getKey();
            PendingReconciliationQueue.Pending pending = entry.getValue();
            if (pending.dueNanos() > now || !activePlayers.add(uuid)) continue;
            dispatched++;
            observePlayer(uuid, pending, sync.reconcile(uuid));
        }
    }

    private void observePlayer(
            UUID uuid, PendingReconciliationQueue.Pending pending, CompletableFuture<Void> future) {
        future.whenComplete((ignored, error) -> {
            if (error == null) {
                pendingPlayers.complete(uuid, pending);
            } else {
                pendingPlayers.retry(uuid, pending, System.nanoTime() + RETRY_NANOS);
                plugin.getLogger().log(Level.WARNING,
                        "Numeral role sync failed for " + uuid + "; retrying.", error);
            }
            activePlayers.remove(uuid);
        });
    }

    private void observeUnlink(
            NumeralRoleAccountRef account,
            PendingReconciliationQueue.Pending pending,
            CompletableFuture<Void> future) {
        future.whenComplete((ignored, error) -> {
            if (error == null) {
                if (pendingUnlinks.complete(account, pending)) {
                    persistUnlinks(false);
                }
            } else {
                pendingUnlinks.retry(account, pending, System.nanoTime() + RETRY_NANOS);
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
                unlinkStore.save(pendingUnlinks.keys());
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
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to close numeral role provider.", exception);
        }
        persistUnlinks(true);
    }
}
