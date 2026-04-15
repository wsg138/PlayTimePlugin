package org.enthusia.playtime.util;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.PlaytimeRepository.JoinRecord;
import org.enthusia.playtime.data.model.MinuteDelta;
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

public final class AsyncWriteQueue implements AutoCloseable {

    private final PlayTimePlugin plugin;
    private final PlaytimeRepository repository;
    private final ConcurrentHashMap<UUID, MinuteDelta> pendingMinutes = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<JoinRecord> pendingJoins = new ConcurrentLinkedQueue<>();
    private final long flushIntervalTicks;
    private final AtomicBoolean flushInProgress = new AtomicBoolean(false);
    private final AtomicBoolean immediateFlushScheduled = new AtomicBoolean(false);

    private volatile BukkitTask flushTask;
    private volatile boolean closed;

    public AsyncWriteQueue(PlayTimePlugin plugin, PlaytimeRepository repository, long flushIntervalTicks) {
        this.plugin = plugin;
        this.repository = repository;
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
        if (delta.totalMinutes() <= 0L) {
            return;
        }
        pendingMinutes.merge(uuid, delta, MinuteDelta::plus);
    }

    public void enqueueJoin(UUID uuid, Instant joinedAt) {
        if (closed) {
            return;
        }
        pendingJoins.add(new JoinRecord(uuid, joinedAt));
        scheduleImmediateFlush();
    }

    public RangeTotals getPendingTotals(UUID uuid) {
        MinuteDelta delta = pendingMinutes.get(uuid);
        return delta == null ? new RangeTotals(0, 0, 0) : delta.toRangeTotals();
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
        List<JoinRecord> joinBatch = drainJoinBatch();

        try {
            if (!joinBatch.isEmpty()) {
                repository.batchRecordJoins(joinBatch);
            }
            if (!minuteBatch.isEmpty()) {
                repository.batchRecordMinutes(minuteBatch, Instant.now());
            }
        } catch (Exception exception) {
            requeueJoinBatch(joinBatch);
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
        JoinRecord record;
        while ((record = pendingJoins.poll()) != null) {
            batch.add(record);
        }
        return batch;
    }

    private void requeueMinuteBatch(Map<UUID, MinuteDelta> batch) {
        for (Map.Entry<UUID, MinuteDelta> entry : batch.entrySet()) {
            pendingMinutes.merge(entry.getKey(), entry.getValue(), MinuteDelta::plus);
        }
    }

    private void requeueJoinBatch(List<JoinRecord> batch) {
        for (JoinRecord record : batch) {
            pendingJoins.add(record);
        }
    }

    private void scheduleImmediateFlush() {
        if (immediateFlushScheduled.compareAndSet(false, true)) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, this::flushAsyncSafely);
        }
    }

    @Override
    public void close() {
        closed = true;
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        waitForActiveFlush();
        flushSyncInternal();
        waitForActiveFlush();
    }

    private void waitForActiveFlush() {
        int spins = 0;
        while (flushInProgress.get() && spins++ < 200) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
