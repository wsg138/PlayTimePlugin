package org.enthusia.playtime.discord;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.AccountLinkedEvent;
import github.scarsz.discordsrv.api.events.AccountUnlinkedEvent;
import github.scarsz.discordsrv.dependencies.jda.api.exceptions.ErrorResponseException;
import github.scarsz.discordsrv.dependencies.jda.api.requests.ErrorResponse;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.service.PlaytimeRuntime;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/** Bounded, retrying bridge between authoritative playtime and DiscordSRV account links. */
public final class DiscordNumeralCoordinator implements AutoCloseable {
    private static final long RETRY_NANOS = 30_000_000_000L;
    private static final int MAX_REQUESTS_PER_SECOND = 8;
    private static final int SWEEP_INTERVAL_SECONDS = 300;

    private final PlayTimePlugin plugin;
    private final NumeralRoleSyncService sync;
    private final PendingUnlinkStore unlinkStore;
    private final Map<UUID, PendingPlayer> pendingPlayers = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingUnlinks = new ConcurrentHashMap<>();
    private final Set<UUID> activePlayers = ConcurrentHashMap.newKeySet();
    private final Set<String> activeUnlinks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong requestSequence = new AtomicLong();
    private final Object fileLock = new Object();
    private BukkitTask task;
    private int secondsSinceSweep = SWEEP_INTERVAL_SECONDS;

    public DiscordNumeralCoordinator(PlayTimePlugin plugin, NumeralRolePolicy policy) throws IOException {
        this.plugin = plugin;
        this.unlinkStore = new PendingUnlinkStore(new File(plugin.getDataFolder(), "pending-discord-numeral-unlinks.yml"));
        this.sync = new NumeralRoleSyncService(policy,
                uuid -> {
                    if (!DiscordSRV.isReady || DiscordSRV.getPlugin() == null
                            || DiscordSRV.getPlugin().getAccountLinkManager() == null) {
                        throw new IllegalStateException("DiscordSRV account links unavailable");
                    }
                    return DiscordSRV.getPlugin().getAccountLinkManager().getDiscordId(uuid);
                },
                uuid -> {
                    PlaytimeRuntime runtime = plugin.runtime();
                    if (runtime == null) throw new IllegalStateException("Playtime runtime unavailable");
                    return runtime.readAuthoritativeActiveMinutes(uuid);
                }, new DiscordSrvNumeralGateway());
        for (String id : unlinkStore.load()) pendingUnlinks.put(id, Long.MIN_VALUE);
    }

    public void start() {
        DiscordSRV.api.subscribe(this);
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::drain, 20L, 20L);
    }

    public void request(UUID uuid) {
        if (!closed.get() && uuid != null) pendingPlayers.put(uuid, new PendingPlayer(Long.MIN_VALUE, requestSequence.incrementAndGet()));
    }

    @Subscribe
    public void linked(AccountLinkedEvent event) {
        request(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void unlinked(AccountUnlinkedEvent event) {
        String discordId = event.getDiscordId();
        if (discordId == null || !discordId.matches("[0-9]{1,20}")) return;
        synchronized (fileLock) {
            if (closed.get()) return;
            pendingUnlinks.put(discordId, Long.MIN_VALUE);
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
        if (!DiscordSRV.isReady || DiscordSRV.getPlugin() == null
                || DiscordSRV.getPlugin().getAccountLinkManager() == null) return;
        try {
            // DiscordSRV owns the link index. Re-enumeration repairs missed events and restarts.
            DiscordSRV.getPlugin().getAccountLinkManager().getLinkedAccounts().values().forEach(this::request);
            secondsSinceSweep = 0;
        } catch (RuntimeException exception) {
            secondsSinceSweep = SWEEP_INTERVAL_SECONDS - 30;
            plugin.getLogger().log(Level.WARNING, "Could not enumerate linked Discord accounts; retrying.", exception);
        }
    }

    private int dispatchUnlinks(long now) {
        int dispatched = 0;
        for (Map.Entry<String, Long> entry : pendingUnlinks.entrySet()) {
            if (dispatched >= MAX_REQUESTS_PER_SECOND) break;
            String id = entry.getKey();
            if (entry.getValue() > now || !activeUnlinks.add(id)) continue;
            dispatched++;
            observeUnlink(id, sync.unlink(id));
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
            if (error == null || isMemberAbsent(error)) pendingPlayers.remove(uuid, pending);
            else {
                pendingPlayers.replace(uuid, pending,
                        new PendingPlayer(System.nanoTime() + RETRY_NANOS, pending.version()));
                plugin.getLogger().log(Level.WARNING, "Discord numeral role sync failed for " + uuid + "; retrying.", error);
            }
            activePlayers.remove(uuid);
        });
    }

    private void observeUnlink(String id, CompletableFuture<Void> future) {
        future.whenComplete((ignored, error) -> {
            if (error == null || isMemberAbsent(error)) {
                pendingUnlinks.remove(id);
                persistUnlinks(false);
            } else {
                pendingUnlinks.put(id, System.nanoTime() + RETRY_NANOS);
                plugin.getLogger().log(Level.WARNING, "Discord numeral role unlink cleanup failed for " + id + "; retrying.", error);
            }
            activeUnlinks.remove(id);
        });
    }

    static boolean isMemberAbsent(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause instanceof ErrorResponseException response
                && response.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER;
    }

    private void persistUnlinks(boolean closing) {
        synchronized (fileLock) {
            if (closed.get() && !closing) return;
            try {
                unlinkStore.save(pendingUnlinks.keySet());
            } catch (IOException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not persist Discord numeral unlink cleanup queue.", exception);
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (task != null) task.cancel();
        DiscordSRV.api.unsubscribe(this);
        persistUnlinks(true);
    }

    private record PendingPlayer(long dueNanos, long version) { }
}
