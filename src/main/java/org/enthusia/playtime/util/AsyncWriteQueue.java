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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.function.LongSupplier;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class AsyncWriteQueue implements AutoCloseable {

    private static final long MIN_TOTAL_MINUTES = 1L;
    private static final int SPINS_PER_SECOND = 100;
    private static final long WAIT_SLEEP_MILLIS = 10L;
    private static final java.util.concurrent.atomic.AtomicInteger ACTIVE_QUEUES =
            new java.util.concurrent.atomic.AtomicInteger();

    private final PlayTimePlugin plugin;
    private final PlaytimeRepository repository;
    private final PerformanceCounters counters;
    private final ConcurrentHashMap<UUID, MinuteDelta> pendingMinutes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, MinuteDelta> acceptedUncommittedMinutes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicLong> acceptedActiveSequences = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, MinuteDelta> inFlightMinutes = new ConcurrentHashMap<>();
    private final Object minuteLedgerLock = new Object();
    private final Object lifecycleLock = new Object();
    /** Lock order is lifecycleLock, writeOwnershipLock, then minuteLedgerLock. Never hold this during I/O. */
    private final Object writeOwnershipLock = new Object();
    private final ConcurrentHashMap<UUID, PlayerProfile> pendingProfiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PlayerProfile> inFlightProfiles = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<JoinRecord> pendingJoins = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<JoinRecord> inFlightJoins = new ConcurrentLinkedQueue<>();
    private final long flushIntervalTicks;
    private final QueueScheduler scheduler;
    private final AtomicBoolean flushInProgress = new AtomicBoolean(false);
    private final AtomicBoolean immediateFlushScheduled = new AtomicBoolean(false);
    private final AtomicBoolean minuteCommitInProgress = new AtomicBoolean(false);
    private final AtomicLong minuteCommitGeneration = new AtomicLong();
    private final AtomicBoolean recoverySettlementPending = new AtomicBoolean();
    private volatile Runnable recoverySettlement = () -> { };
    private volatile RecoverySnapshot pendingRecovery;
    private UUID shutdownRecoveryBatchId;

    private volatile BukkitTask flushTask;
    private QueueState state = QueueState.RUNNING;
    private boolean countedActive;

    public enum TransitionResult { SUCCESS, TIMED_OUT, WRITE_FAILED, HANDOFF_ABORTED }
    public enum EnqueueResult { ACCEPTED, HANDOFF_PAUSED, CLOSED }
    public enum QueueState { RUNNING, HANDOFF_PAUSED, CLOSED }

    public AsyncWriteQueue(PlayTimePlugin plugin, PlaytimeRepository repository, PerformanceCounters counters, long flushIntervalTicks) {
        this(plugin, repository, counters, flushIntervalTicks, new QueueScheduler() {
            @Override public BukkitTask schedulePeriodic(Runnable task, long intervalTicks) {
                return Bukkit.getScheduler().runTaskTimerAsynchronously(
                        plugin, task, intervalTicks, intervalTicks);
            }
            @Override public void scheduleImmediate(Runnable task) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            }
        });
    }

    AsyncWriteQueue(PlayTimePlugin plugin, PlaytimeRepository repository, PerformanceCounters counters,
                    long flushIntervalTicks, QueueScheduler scheduler) {
        this.plugin = plugin;
        this.repository = repository;
        this.counters = counters;
        this.flushIntervalTicks = flushIntervalTicks;
        this.scheduler = scheduler;
    }

    public void start() {
        synchronized (lifecycleLock) {
            if (state != QueueState.RUNNING || flushTask != null) return;
            if (plugin == null) return;
            flushTask = scheduler.schedulePeriodic(this::flushAsyncSafely, flushIntervalTicks);
            if (!countedActive) {
                countedActive = true;
                ACTIVE_QUEUES.incrementAndGet();
            }
        }
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
            synchronized (writeOwnershipLock) {
                synchronized (minuteLedgerLock) {
                    pendingMinutes.merge(uuid, delta, MinuteDelta::plus);
                    acceptedUncommittedMinutes.merge(uuid, delta, MinuteDelta::plus);
                    if (delta.activeMinutes() > 0L) {
                        acceptedActiveSequences.computeIfAbsent(uuid, ignored -> new AtomicLong()).addAndGet(delta.activeMinutes());
                    }
                    minuteCommitGeneration.incrementAndGet();
                }
            }
        }
        counters.minuteDeltasQueued.increment();
        return EnqueueResult.ACCEPTED;
    }

    public EnqueueResult enqueueJoin(UUID uuid, Instant joinedAt) {
        synchronized (lifecycleLock) {
            EnqueueResult result = enqueueResult();
            if (result != EnqueueResult.ACCEPTED) return result;
            synchronized (writeOwnershipLock) {
                pendingJoins.add(new JoinRecord(uuid, joinedAt));
            }
        }
        scheduleImmediateFlush();
        return EnqueueResult.ACCEPTED;
    }

    public EnqueueResult enqueueJoinWithProfile(UUID uuid, Instant joinedAt, PlayerProfile profile) {
        if (profile == null || profile.uuid() == null || !uuid.equals(profile.uuid())) return EnqueueResult.CLOSED;
        synchronized (lifecycleLock) {
            EnqueueResult result = enqueueResult();
            if (result != EnqueueResult.ACCEPTED) return result;
            synchronized (writeOwnershipLock) {
                pendingProfiles.put(uuid, profile);
                pendingJoins.add(new JoinRecord(uuid, joinedAt));
            }
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
            synchronized (writeOwnershipLock) {
                pendingProfiles.put(profile.uuid(), profile);
            }
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
            synchronized (writeOwnershipLock) {
                pendingProfiles.put(profile.uuid(), profile);
            }
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
        synchronized (lifecycleLock) {
            if (state == QueueState.CLOSED) return;
        }
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
        RecoverySnapshot recovery = pendingRecovery;
        if (recovery != null) {
            repository.applyRecoveryBatch(recovery);
            synchronized (lifecycleLock) {
                if (pendingRecovery == recovery) {
                    pendingRecovery = null;
                }
            }
            settleRecoveryJournal();
            return;
        }
        Map<UUID, MinuteDelta> minuteBatch = drainMinuteBatch();
        Map<UUID, PlayerProfile> profileBatch = drainProfileBatch();
        List<JoinRecord> joinBatch = drainJoinBatch();
        boolean joinsCommitted = false;
        boolean profilesCommitted = false;
        boolean minutesCommitted = false;

        try {
            if (!joinBatch.isEmpty()) {
                repository.batchRecordJoins(joinBatch);
                joinsCommitted = true;
                removeCommittedJoins(joinBatch);
            }
            if (!profileBatch.isEmpty()) {
                repository.batchUpsertPlayerProfiles(new ArrayList<>(profileBatch.values()));
                profilesCommitted = true;
                removeCommittedProfiles(profileBatch);
            }
            if (!minuteBatch.isEmpty()) {
                minuteCommitInProgress.set(true);
                try {
                    repository.batchRecordMinutes(minuteBatch, Instant.now());
                    synchronized (writeOwnershipLock) {
                        synchronized (minuteLedgerLock) {
                            confirmMinuteBatch(minuteBatch);
                        }
                        minuteBatch.forEach((uuid, delta) -> inFlightMinutes.remove(uuid, delta));
                        minuteCommitGeneration.incrementAndGet();
                    }
                    minutesCommitted = true;
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
            if (!minutesCommitted) {
                requeueMinuteBatch(minuteBatch);
            }
            throw exception;
        }
        try {
            settleRecoveryJournal();
        } catch (RuntimeException cleanupFailure) {
            plugin.getLogger().log(Level.WARNING,
                    "Playtime recovery journal cleanup failed after SQL was committed; it will be retried safely.", cleanupFailure);
        }
    }

    private Map<UUID, MinuteDelta> drainMinuteBatch() {
        Map<UUID, MinuteDelta> batch = new ConcurrentHashMap<>();
        synchronized (writeOwnershipLock) {
            for (Map.Entry<UUID, MinuteDelta> entry : pendingMinutes.entrySet()) {
                if (pendingMinutes.remove(entry.getKey(), entry.getValue())) {
                    batch.put(entry.getKey(), entry.getValue());
                }
            }
            inFlightMinutes.putAll(batch);
        }
        return batch;
    }

    private List<JoinRecord> drainJoinBatch() {
        List<JoinRecord> batch = new ArrayList<>();
        synchronized (writeOwnershipLock) {
            while (true) {
                JoinRecord record = pendingJoins.poll();
                if (record == null) break;
                batch.add(record);
                inFlightJoins.add(record);
            }
        }
        return batch;
    }

    private Map<UUID, PlayerProfile> drainProfileBatch() {
        Map<UUID, PlayerProfile> batch = new ConcurrentHashMap<>();
        synchronized (writeOwnershipLock) {
            for (Map.Entry<UUID, PlayerProfile> entry : pendingProfiles.entrySet()) {
                if (pendingProfiles.remove(entry.getKey(), entry.getValue())) {
                    batch.put(entry.getKey(), entry.getValue());
                }
            }
            inFlightProfiles.putAll(batch);
        }
        return batch;
    }

    private void requeueMinuteBatch(Map<UUID, MinuteDelta> batch) {
        synchronized (writeOwnershipLock) {
            for (Map.Entry<UUID, MinuteDelta> entry : batch.entrySet()) {
                pendingMinutes.merge(entry.getKey(), entry.getValue(), MinuteDelta::plus);
                inFlightMinutes.remove(entry.getKey(), entry.getValue());
            }
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
        synchronized (writeOwnershipLock) {
            for (JoinRecord record : batch) {
                pendingJoins.add(record);
                inFlightJoins.remove(record);
            }
        }
    }

    private void requeueProfileBatch(Map<UUID, PlayerProfile> batch) {
        synchronized (writeOwnershipLock) {
            for (Map.Entry<UUID, PlayerProfile> entry : batch.entrySet()) {
                pendingProfiles.merge(entry.getKey(), entry.getValue(), this::newerProfile);
                inFlightProfiles.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private void removeCommittedJoins(List<JoinRecord> batch) {
        synchronized (writeOwnershipLock) {
            batch.forEach(inFlightJoins::remove);
        }
    }

    private void removeCommittedProfiles(Map<UUID, PlayerProfile> batch) {
        synchronized (writeOwnershipLock) {
            batch.forEach((uuid, profile) -> inFlightProfiles.remove(uuid, profile));
        }
    }

    private PlayerProfile newerProfile(PlayerProfile pending, PlayerProfile candidate) {
        // Equal timestamps retain pending data because it was accepted later.
        return pending.seenAt().compareTo(candidate.seenAt()) >= 0 ? pending : candidate;
    }

    private void scheduleImmediateFlush() {
        synchronized (lifecycleLock) {
            if (state != QueueState.RUNNING || !plugin.isEnabled()) {
                return;
            }
            if (immediateFlushScheduled.compareAndSet(false, true)) {
                try {
                    scheduler.scheduleImmediate(this::flushAsyncSafely);
                } catch (IllegalPluginAccessException exception) {
                    immediateFlushScheduled.set(false);
                    if (plugin.isEnabled()) throw exception;
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
        synchronized (lifecycleLock) {
            if (flushTask != null) {
                flushTask.cancel();
                flushTask = null;
            }
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
            releaseActiveCount();
            minuteCommitGeneration.incrementAndGet();
        }
        return TransitionResult.SUCCESS;
    }

    public TransitionResult shutdown(int timeoutSeconds) {
        synchronized (lifecycleLock) {
            if (state == QueueState.CLOSED) {
                return hasOutstandingWork() ? TransitionResult.WRITE_FAILED : TransitionResult.SUCCESS;
            }
            state = QueueState.CLOSED;
            releaseActiveCount();
            minuteCommitGeneration.incrementAndGet();
            if (flushTask != null) {
                flushTask.cancel();
                flushTask = null;
            }
        }
        if (!waitForActiveFlush(timeoutSeconds)) {
            return TransitionResult.TIMED_OUT;
        }
        TransitionResult result = flushSyncInternal();
        return result == TransitionResult.SUCCESS && hasOutstandingWork() ? TransitionResult.WRITE_FAILED : result;
    }

    public boolean isFlushInProgressForShutdown() {
        return flushInProgress.get();
    }

    public boolean hasOutstandingWorkForShutdown() {
        return hasOutstandingWork();
    }

    public void restoreRecoverySnapshot(RecoverySnapshot snapshot, Runnable onDurablySettled) {
        if (snapshot == null || snapshot.isEmpty()) return;
        synchronized (lifecycleLock) {
            if (state != QueueState.RUNNING) return;
            recoverySettlement = onDurablySettled == null ? () -> { } : onDurablySettled;
            recoverySettlementPending.set(true);
            pendingRecovery = snapshot;
            shutdownRecoveryBatchId = snapshot.batchId();
        }
    }

    public void closeDatabaseAfterFlush(Runnable closeDatabase, int maxWaitSeconds,
                                        Consumer<RecoverySnapshot> persistRecovery) {
        CompletableFuture.runAsync(() -> {
            long deadline = System.nanoTime() + Math.max(1, maxWaitSeconds) * 1_000_000_000L;
            while (flushInProgress.get() && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(WAIT_SLEEP_MILLIS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (flushInProgress.get()) {
                try {
                    persistRecovery.accept(recoverySnapshot());
                } catch (RuntimeException failure) {
                    plugin.getLogger().log(Level.SEVERE, "Could not write the playtime shutdown recovery journal; database remains open.", failure);
                    return;
                }
                plugin.getLogger().severe("Playtime database left open because an in-flight queue flush did not settle.");
                return;
            }
            if (hasOutstandingWork()) {
                try {
                    persistRecovery.accept(recoverySnapshot());
                } catch (RuntimeException failure) {
                    plugin.getLogger().log(Level.SEVERE, "Could not write the playtime shutdown recovery journal; database remains open.", failure);
                    return;
                }
            }
            closeDatabase.run();
        });
    }

    public void closeDatabaseAfterFlush(Runnable closeDatabase, int maxWaitSeconds) {
        closeDatabaseAfterFlush(closeDatabase, maxWaitSeconds, ignored -> {
            throw new IllegalStateException("Shutdown recovery journal is unavailable");
        });
    }

    private void settleRecoveryJournal() {
        if (pendingRecovery == null && recoverySettlementPending.get()
                && recoverySettlementPending.compareAndSet(true, false)) {
            recoverySettlement.run();
            synchronized (lifecycleLock) {
                if (pendingRecovery == null) {
                    shutdownRecoveryBatchId = null;
                }
            }
        }
    }

    public RecoverySnapshot recoverySnapshot() {
        synchronized (lifecycleLock) {
            synchronized (writeOwnershipLock) {
                Map<UUID, MinuteDelta> minutes = new ConcurrentHashMap<>();
                pendingMinutes.forEach((uuid, delta) -> minutes.merge(uuid, delta, MinuteDelta::plus));
                inFlightMinutes.forEach((uuid, delta) -> minutes.merge(uuid, delta, MinuteDelta::plus));
                Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();
                inFlightProfiles.forEach((uuid, profile) -> profiles.merge(uuid, profile, this::newerProfile));
                pendingProfiles.forEach((uuid, profile) -> profiles.merge(uuid, profile, this::newerProfile));
                List<JoinRecord> joins = new ArrayList<>(inFlightJoins);
                joins.addAll(pendingJoins);
                if (shutdownRecoveryBatchId == null) {
                    shutdownRecoveryBatchId = UUID.randomUUID();
                }
                return new RecoverySnapshot(shutdownRecoveryBatchId, Map.copyOf(minutes), Map.copyOf(profiles), List.copyOf(joins));
            }
        }
    }

    public record RecoverySnapshot(UUID batchId, Map<UUID, MinuteDelta> minutes, Map<UUID, PlayerProfile> profiles,
                                   List<JoinRecord> joins) {
        public RecoverySnapshot(Map<UUID, MinuteDelta> minutes, Map<UUID, PlayerProfile> profiles,
                                List<JoinRecord> joins) {
            this(UUID.randomUUID(), minutes, profiles, joins);
        }
        public boolean isEmpty() {
            return minutes.isEmpty() && profiles.isEmpty() && joins.isEmpty();
        }
    }

    private boolean hasOutstandingWork() {
        synchronized (writeOwnershipLock) {
            return !pendingMinutes.isEmpty() || !inFlightMinutes.isEmpty() || !pendingProfiles.isEmpty() || !inFlightProfiles.isEmpty()
                    || !pendingJoins.isEmpty() || !inFlightJoins.isEmpty();
        }
    }

    private EnqueueResult enqueueResult() {
        return switch (state) {
            case RUNNING -> EnqueueResult.ACCEPTED;
            case HANDOFF_PAUSED -> EnqueueResult.HANDOFF_PAUSED;
            case CLOSED -> EnqueueResult.CLOSED;
        };
    }

    QueueState stateForTesting() {
        synchronized (lifecycleLock) {
            return state;
        }
    }

    OutstandingWork outstandingWorkForTesting() {
        synchronized (writeOwnershipLock) {
            return new OutstandingWork(pendingMinutes.size() + inFlightMinutes.size(),
                    pendingJoins.size() + inFlightJoins.size(),
                    pendingProfiles.size() + inFlightProfiles.size());
        }
    }

    record OutstandingWork(int minutePlayers, int joins, int profiles) {}

    private void releaseActiveCount() {
        if (countedActive) {
            countedActive = false;
            ACTIVE_QUEUES.decrementAndGet();
        }
    }

    public static int activeQueueCountForTesting() {
        return ACTIVE_QUEUES.get();
    }

    interface QueueScheduler {
        BukkitTask schedulePeriodic(Runnable task, long intervalTicks);
        void scheduleImmediate(Runnable task);
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
