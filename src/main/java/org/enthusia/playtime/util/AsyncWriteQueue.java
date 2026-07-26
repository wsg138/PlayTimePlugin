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
import java.util.concurrent.atomic.AtomicLong;

public final class AsyncWriteQueue implements AutoCloseable {

    private static final long MIN_TOTAL_MINUTES = 1L;
    private static final int SPINS_PER_SECOND = 100;
    private static final long WAIT_SLEEP_MILLIS = 10L;

    private final PlayTimePlugin plugin;
    private final PlaytimeRepository repository;
    private final PerformanceCounters counters;
    private final ConcurrentHashMap<UUID, MinuteDelta> pendingMinutes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, MinuteDelta> acceptedUncommittedMinutes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicLong> acceptedActiveSequences = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, MinuteDelta> inFlightMinutes = new ConcurrentHashMap<>();
    private final Object minuteLedgerLock = new Object();
    private final Object lifecycleLock = new Object();
    private final ConcurrentHashMap<UUID, PlayerProfile> pendingProfiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PlayerProfile> inFlightProfiles = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<JoinRecord> pendingJoins = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<JoinRecord> inFlightJoins = new ConcurrentLinkedQueue<>();
    private final long flushIntervalTicks;
    private final AtomicBoolean flushInProgress = new AtomicBoolean(false);
    private final AtomicBoolean immediateFlushScheduled = new AtomicBoolean(false);
    private final AtomicBoolean minuteCommitInProgress = new AtomicBoolean(false);
    private final AtomicLong minuteCommitGeneration = new AtomicLong();

    private volatile BukkitTask flushTask;
    private QueueState state = QueueState.RUNNING;

    public enum TransitionResult { SUCCESS, TIMED_OUT, WRITE_FAILED, HANDOFF_ABORTED }
    public enum EnqueueResult { ACCEPTED, HANDOFF_PAUSED, CLOSED }
    public enum QueueState { RUNNING, HANDOFF_PAUSED, CLOSED }

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

    public EnqueueResult enqueueMinute(UUID uuid, int activeMinutes, int afkMinutes) {
        MinuteDelta delta = new MinuteDelta(activeMinutes, afkMinutes);
        if (delta.totalMinutes() < MIN_TOTAL_MINUTES) {
            return EnqueueResult.ACCEPTED;
        }
        synchronized (lifecycleLock) {
            EnqueueResult result = enqueueResult();
            if (result != EnqueueResult.ACCEPTED) {
                return result;
            }
            synchronized (minuteLedgerLock) {
                pendingMinutes.merge(uuid, delta, MinuteDelta::plus);
                acceptedUncommittedMinutes.merge(uuid, delta, MinuteDelta::plus);
                if (delta.activeMinutes() > 0L) {
                    acceptedActiveSequences.computeIfAbsent(uuid, ignored -> new AtomicLong()).addAndGet(delta.activeMinutes());
                }
                minuteCommitGeneration.incrementAndGet();
            }
        }
        counters.minuteDeltasQueued.increment();
        return EnqueueResult.ACCEPTED;
    }

    public EnqueueResult enqueueJoin(UUID uuid, Instant joinedAt) {
        synchronized (lifecycleLock) {
            EnqueueResult result = enqueueResult();
            if (result != EnqueueResult.ACCEPTED) return result;
            pendingJoins.add(new JoinRecord(uuid, joinedAt));
        }
        scheduleImmediateFlush();
        return EnqueueResult.ACCEPTED;
    }

    public EnqueueResult enqueuePlayerProfile(PlayerProfile profile) {
        if (profile == null || profile.uuid() == null) {
            return EnqueueResult.CLOSED;
        }
        synchronized (lifecycleLock) {
            EnqueueResult result = enqueueResult();
            if (result != EnqueueResult.ACCEPTED) return result;
            pendingProfiles.put(profile.uuid(), profile);
        }
        scheduleImmediateFlush();
        return EnqueueResult.ACCEPTED;
    }

    public void enqueuePlayerProfileForShutdown(PlayerProfile profile) {
        if (profile == null || profile.uuid() == null) {
            return;
        }
        synchronized (lifecycleLock) {
            if (state == QueueState.CLOSED) return;
            pendingProfiles.put(profile.uuid(), profile);
        }
    }

    public RangeTotals getPendingTotals(UUID uuid) {
        MinuteDelta delta = pendingMinutes.get(uuid);
        return delta == null ? new RangeTotals(0, 0, 0) : delta.toRangeTotals();
    }

    /** Returns a generation-verified durable-plus-uncommitted active total without holding a lock during SQL. */
    public long getEffectiveActiveMinutes(UUID uuid, LongSupplier durableActiveMinutes) {
        return readEffectiveActiveSnapshot(uuid, durableActiveMinutes).map(EffectiveActiveSnapshot::activeMinutes).orElse(-1L);
    }

    public java.util.Optional<EffectiveActiveSnapshot> readEffectiveActiveSnapshot(UUID uuid, LongSupplier durableActiveMinutes) {
        for (int attempt = 0; attempt < 3; attempt++) {
            LedgerCutoff cutoff = ledgerCutoff(uuid);
            long durable = Math.max(0L, durableActiveMinutes.getAsLong());
            if (cutoff.revision() == minuteCommitGeneration.get() && !minuteCommitInProgress.get()) {
                return java.util.Optional.of(new EffectiveActiveSnapshot(durable + cutoff.uncommittedActive(), cutoff.sequence(), cutoff.revision()));
            }
        }
        return java.util.Optional.empty();
    }

    public RangeTotals getAcceptedUncommittedTotals(UUID uuid) {
        synchronized (minuteLedgerLock) {
            MinuteDelta delta = acceptedUncommittedMinutes.get(uuid);
            return delta == null ? new RangeTotals(0, 0, 0) : delta.toRangeTotals();
        }
    }

    public long acceptedActiveSequence(UUID uuid) {
        AtomicLong sequence = acceptedActiveSequences.get(uuid);
        return sequence == null ? 0L : sequence.get();
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

    public TransitionResult flushNow() {
        return flushSyncInternal();
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

    private TransitionResult flushSyncInternal() {
        if (!flushInProgress.compareAndSet(false, true)) {
            return TransitionResult.TIMED_OUT;
        }
        try {
            flushInternal();
            return TransitionResult.SUCCESS;
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to flush buffered playtime writes.", exception);
            return TransitionResult.WRITE_FAILED;
        } finally {
            flushInProgress.set(false);
        }
    }

    private void flushInternal() throws Exception {
        Map<UUID, MinuteDelta> minuteBatch = drainMinuteBatch();
        Map<UUID, PlayerProfile> profileBatch = drainProfileBatch();
        List<JoinRecord> joinBatch = drainJoinBatch();
        boolean joinsCommitted = false;
        boolean profilesCommitted = false;

        try {
            if (!joinBatch.isEmpty()) {
                repository.batchRecordJoins(joinBatch);
                joinsCommitted = true;
                inFlightJoins.clear();
            }
            if (!profileBatch.isEmpty()) {
                repository.batchUpsertPlayerProfiles(new ArrayList<>(profileBatch.values()));
                profilesCommitted = true;
                inFlightProfiles.clear();
            }
            if (!minuteBatch.isEmpty()) {
                minuteCommitInProgress.set(true);
                try {
                    repository.batchRecordMinutes(minuteBatch, Instant.now());
                    synchronized (minuteLedgerLock) {
                        confirmMinuteBatch(minuteBatch);
                    }
                    inFlightMinutes.clear();
                    minuteCommitGeneration.incrementAndGet();
                } finally {
                    minuteCommitInProgress.set(false);
                }
            }
            if (!joinBatch.isEmpty() || !profileBatch.isEmpty() || !minuteBatch.isEmpty()) {
                counters.flushBatches.increment();
            }
        } catch (Exception exception) {
            if (!joinsCommitted) {
                requeueJoinBatch(joinBatch);
            }
            if (!profilesCommitted) {
                requeueProfileBatch(profileBatch);
            }
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
        inFlightMinutes.putAll(batch);
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
            inFlightJoins.add(record);
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
        inFlightProfiles.putAll(batch);
        return batch;
    }

    private void requeueMinuteBatch(Map<UUID, MinuteDelta> batch) {
        for (Map.Entry<UUID, MinuteDelta> entry : batch.entrySet()) {
            pendingMinutes.merge(entry.getKey(), entry.getValue(), MinuteDelta::plus);
        }
        inFlightMinutes.clear();
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
        inFlightJoins.clear();
    }

    private void requeueProfileBatch(Map<UUID, PlayerProfile> batch) {
        for (Map.Entry<UUID, PlayerProfile> entry : batch.entrySet()) {
            pendingProfiles.put(entry.getKey(), entry.getValue());
        }
        inFlightProfiles.clear();
    }

    private void scheduleImmediateFlush() {
        synchronized (lifecycleLock) {
            if (state != QueueState.RUNNING || !plugin.isEnabled()) {
                return;
            }
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

    public TransitionResult prepareHandoff(int timeoutSeconds) {
        synchronized (lifecycleLock) {
            if (state != QueueState.RUNNING) return TransitionResult.HANDOFF_ABORTED;
            state = QueueState.HANDOFF_PAUSED;
            minuteCommitGeneration.incrementAndGet();
        }
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        if (!waitForActiveFlush(timeoutSeconds)) {
            return TransitionResult.TIMED_OUT;
        }
        TransitionResult result = flushSyncInternal();
        return result == TransitionResult.SUCCESS && hasOutstandingWork() ? TransitionResult.WRITE_FAILED : result;
    }

    public void abortHandoff() {
        synchronized (lifecycleLock) {
            if (state != QueueState.HANDOFF_PAUSED) return;
            state = QueueState.RUNNING;
            minuteCommitGeneration.incrementAndGet();
        }
        start();
    }

    public TransitionResult completeHandoff() {
        synchronized (lifecycleLock) {
            if (state != QueueState.HANDOFF_PAUSED || hasOutstandingWork()) {
                return TransitionResult.HANDOFF_ABORTED;
            }
            state = QueueState.CLOSED;
            minuteCommitGeneration.incrementAndGet();
        }
        return TransitionResult.SUCCESS;
    }

    public TransitionResult shutdown(int timeoutSeconds) {
        synchronized (lifecycleLock) {
            state = QueueState.CLOSED;
            minuteCommitGeneration.incrementAndGet();
        }
        if (flushTask != null) {
            flushTask.cancel();
        }
        if (!waitForActiveFlush(timeoutSeconds)) {
            return TransitionResult.TIMED_OUT;
        }
        TransitionResult result = flushSyncInternal();
        return result == TransitionResult.SUCCESS && hasOutstandingWork() ? TransitionResult.WRITE_FAILED : result;
    }

    private boolean hasOutstandingWork() {
        return !pendingMinutes.isEmpty() || !inFlightMinutes.isEmpty() || !pendingProfiles.isEmpty() || !inFlightProfiles.isEmpty()
                || !pendingJoins.isEmpty() || !inFlightJoins.isEmpty();
    }

    private EnqueueResult enqueueResult() {
        return switch (state) {
            case RUNNING -> EnqueueResult.ACCEPTED;
            case HANDOFF_PAUSED -> EnqueueResult.HANDOFF_PAUSED;
            case CLOSED -> EnqueueResult.CLOSED;
        };
    }

    private LedgerCutoff ledgerCutoff(UUID uuid) {
        synchronized (minuteLedgerLock) {
            MinuteDelta delta = acceptedUncommittedMinutes.get(uuid);
            AtomicLong sequence = acceptedActiveSequences.get(uuid);
            return new LedgerCutoff(delta == null ? 0L : delta.activeMinutes(), sequence == null ? 0L : sequence.get(), minuteCommitGeneration.get());
        }
    }

    public record EffectiveActiveSnapshot(long activeMinutes, long acceptedActiveSequence, long revision) {
    }

    private record LedgerCutoff(long uncommittedActive, long sequence, long revision) {
    }

    private boolean waitForActiveFlush(int timeoutSeconds) {
        int spins = 0;
        int maxSpins = Math.max(1, timeoutSeconds) * SPINS_PER_SECOND;
        while (flushInProgress.get() && spins < maxSpins) {
            spins++;
            try {
                Thread.sleep(WAIT_SLEEP_MILLIS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !flushInProgress.get();
    }

    @Override
    public void close() {
        shutdown(10);
    }
}
