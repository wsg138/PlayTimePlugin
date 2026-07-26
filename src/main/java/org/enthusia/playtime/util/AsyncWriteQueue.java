package org.enthusia.playtime.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.PlaytimeRepository.JoinRecord;
import org.enthusia.playtime.data.model.MinuteDelta;
import org.enthusia.playtime.data.model.PlayerProfile;
import org.enthusia.playtime.data.model.RangeTotals;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.function.LongSupplier;

public final class AsyncWriteQueue implements AutoCloseable {

    private static final long MIN_TOTAL_MINUTES = 1L;
    private static final int SPINS_PER_SECOND = 100;
    private static final long WAIT_SLEEP_MILLIS = 10L;

    private final PlayTimePlugin plugin;
    private final PlaytimeRepository repository;
    private final PerformanceCounters counters;
    private final ConcurrentHashMap<UUID, MinuteDelta> pendingMinutes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, MinuteDelta> acceptedUncommittedMinutes = new ConcurrentHashMap<>();
    private final Object minuteLedgerLock = new Object();
    private final ConcurrentHashMap<UUID, PlayerProfile> pendingProfiles = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<JoinRecord> pendingJoins = new ConcurrentLinkedQueue<>();
    private final long flushIntervalTicks;
    private final AtomicBoolean flushInProgress = new AtomicBoolean(false);
    private final AtomicBoolean immediateFlushScheduled = new AtomicBoolean(false);

    private volatile BukkitTask flushTask;
    private volatile boolean closed;

    public AsyncWriteQueue(PlayTimePlugin plugin, PlaytimeRepository repository, PerformanceCounters counters, long flushIntervalTicks) {
        this.plugin = plugin;
        this.repository = repository;
        this.counters = counters;
        this.flushIntervalTicks = flushIntervalTicks;
    }

    public void start() {
        if (flushTask != null) {
            return;
        }
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushAsyncSafely, flushIntervalTicks, flushIntervalTicks);
    }

    public void enqueueMinute(UUID uuid, int activeMinutes, int afkMinutes) {
        if (closed) {
            return;
        }
        MinuteDelta delta = new MinuteDelta(activeMinutes, afkMinutes);
        if (delta.totalMinutes() < MIN_TOTAL_MINUTES) {
            return;
        }
        synchronized (minuteLedgerLock) {
            pendingMinutes.merge(uuid, delta, MinuteDelta::plus);
            acceptedUncommittedMinutes.merge(uuid, delta, MinuteDelta::plus);
        }
        counters.minuteDeltasQueued.increment();
    }

    public void enqueueJoin(UUID uuid, Instant joinedAt) {
        if (closed) {
            return;
        }
        pendingJoins.add(new JoinRecord(uuid, joinedAt));
        scheduleImmediateFlush();
    }

    public void enqueuePlayerProfile(PlayerProfile profile) {
        if (closed || profile == null || profile.uuid() == null) {
            return;
        }
        pendingProfiles.put(profile.uuid(), profile);
        scheduleImmediateFlush();
    }

    public void enqueuePlayerProfileForShutdown(PlayerProfile profile) {
        if (closed || profile == null || profile.uuid() == null) {
            return;
        }
        pendingProfiles.put(profile.uuid(), profile);
    }

    public RangeTotals getPendingTotals(UUID uuid) {
        MinuteDelta delta = pendingMinutes.get(uuid);
        return delta == null ? new RangeTotals(0, 0, 0) : delta.toRangeTotals();
    }

    /**
     * Returns a stable durable-plus-uncommitted active total. The supplier and transaction confirmation
     * share one lock so a successfully committed batch cannot be counted in both places.
     */
    public long getEffectiveActiveMinutes(UUID uuid, LongSupplier durableActiveMinutes) {
        synchronized (minuteLedgerLock) {
            MinuteDelta uncommitted = acceptedUncommittedMinutes.get(uuid);
            return Math.max(0L, durableActiveMinutes.getAsLong()) + (uncommitted == null ? 0L : uncommitted.activeMinutes());
        }
    }

    public RangeTotals getAcceptedUncommittedTotals(UUID uuid) {
        synchronized (minuteLedgerLock) {
            MinuteDelta delta = acceptedUncommittedMinutes.get(uuid);
            return delta == null ? new RangeTotals(0, 0, 0) : delta.toRangeTotals();
        }
    }

    public RangeTotals getPendingTotalsForServer() {
        long active = 0L;
        long afk = 0L;
        for (MinuteDelta delta : pendingMinutes.values()) {
            active += delta.activeMinutes();
            afk += delta.afkMinutes();
        }
        return new RangeTotals(active, afk, active + afk);
    }

    public void flushNow() {
        flushSyncInternal();
    }

    private void flushAsyncSafely() {
        immediateFlushScheduled.set(false);
        if (!flushInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            flushInternal();
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to flush buffered playtime writes asynchronously.", exception);
        } finally {
            flushInProgress.set(false);
        }
    }

    private void flushSyncInternal() {
        if (!flushInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            flushInternal();
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to flush buffered playtime writes.", exception);
        } finally {
            flushInProgress.set(false);
        }
    }

    private void flushInternal() throws Exception {
        Map<UUID, MinuteDelta> minuteBatch = drainMinuteBatch();
        Map<UUID, PlayerProfile> profileBatch = drainProfileBatch();
        List<JoinRecord> joinBatch = drainJoinBatch();

        try {
            if (!joinBatch.isEmpty()) {
                repository.batchRecordJoins(joinBatch);
            }
            if (!profileBatch.isEmpty()) {
                repository.batchUpsertPlayerProfiles(new ArrayList<>(profileBatch.values()));
            }
            if (!minuteBatch.isEmpty()) {
                synchronized (minuteLedgerLock) {
                    repository.batchRecordMinutes(minuteBatch, Instant.now());
                    confirmMinuteBatch(minuteBatch);
                }
            }
            if (!joinBatch.isEmpty() || !profileBatch.isEmpty() || !minuteBatch.isEmpty()) {
                counters.flushBatches.increment();
            }
        } catch (Exception exception) {
            requeueJoinBatch(joinBatch);
            requeueProfileBatch(profileBatch);
            requeueMinuteBatch(minuteBatch);
            throw exception;
        }
    }

    private Map<UUID, MinuteDelta> drainMinuteBatch() {
        Map<UUID, MinuteDelta> batch = new ConcurrentHashMap<>();
        for (Map.Entry<UUID, MinuteDelta> entry : pendingMinutes.entrySet()) {
            if (pendingMinutes.remove(entry.getKey(), entry.getValue())) {
                batch.put(entry.getKey(), entry.getValue());
            }
        }
        return batch;
    }

    private List<JoinRecord> drainJoinBatch() {
        List<JoinRecord> batch = new ArrayList<>();
        while (true) {
            JoinRecord record = pendingJoins.poll();
            if (record == null) {
                break;
            }
            batch.add(record);
        }
        return batch;
    }

    private Map<UUID, PlayerProfile> drainProfileBatch() {
        Map<UUID, PlayerProfile> batch = new ConcurrentHashMap<>();
        for (Map.Entry<UUID, PlayerProfile> entry : pendingProfiles.entrySet()) {
            if (pendingProfiles.remove(entry.getKey(), entry.getValue())) {
                batch.put(entry.getKey(), entry.getValue());
            }
        }
        return batch;
    }

    private void requeueMinuteBatch(Map<UUID, MinuteDelta> batch) {
        for (Map.Entry<UUID, MinuteDelta> entry : batch.entrySet()) {
            pendingMinutes.merge(entry.getKey(), entry.getValue(), MinuteDelta::plus);
        }
    }

    private void confirmMinuteBatch(Map<UUID, MinuteDelta> batch) {
        for (Map.Entry<UUID, MinuteDelta> entry : batch.entrySet()) {
            acceptedUncommittedMinutes.computeIfPresent(entry.getKey(), (ignored, current) -> {
                MinuteDelta confirmed = entry.getValue();
                long active = Math.max(0L, current.activeMinutes() - confirmed.activeMinutes());
                long afk = Math.max(0L, current.afkMinutes() - confirmed.afkMinutes());
                return active + afk == 0L ? null : new MinuteDelta(active, afk);
            });
        }
    }

    private void requeueJoinBatch(List<JoinRecord> batch) {
        for (JoinRecord record : batch) {
            pendingJoins.add(record);
        }
    }

    private void requeueProfileBatch(Map<UUID, PlayerProfile> batch) {
        for (Map.Entry<UUID, PlayerProfile> entry : batch.entrySet()) {
            pendingProfiles.put(entry.getKey(), entry.getValue());
        }
    }

    private void scheduleImmediateFlush() {
        if (closed || !plugin.isEnabled()) {
            return;
        }
        if (immediateFlushScheduled.compareAndSet(false, true)) {
            try {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, this::flushAsyncSafely);
            } catch (IllegalPluginAccessException exception) {
                immediateFlushScheduled.set(false);
                if (plugin.isEnabled()) {
                    throw exception;
                }
            }
        }
    }

    @Override
    public void close() {
        close(10);
    }

    public void close(int timeoutSeconds) {
        closed = true;
        if (flushTask != null) {
            flushTask.cancel();
        }
        waitForActiveFlush(timeoutSeconds);
        flushSyncInternal();
        waitForActiveFlush(timeoutSeconds);
    }

    private void waitForActiveFlush(int timeoutSeconds) {
        int spins = 0;
        int maxSpins = Math.max(1, timeoutSeconds) * SPINS_PER_SECOND;
        while (flushInProgress.get() && spins < maxSpins) {
            spins++;
            try {
                Thread.sleep(WAIT_SLEEP_MILLIS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
