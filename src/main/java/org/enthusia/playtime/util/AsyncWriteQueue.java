package org.enthusia.playtime.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.WriteBatch;
import org.enthusia.playtime.data.PlaytimeRepository.JoinRecord;
import org.enthusia.playtime.data.model.MinuteDelta;
import org.enthusia.playtime.data.model.PlayerProfile;
import org.enthusia.playtime.data.model.RangeTotals;

import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
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
    private final Object minuteLedgerLock = new Object();
    private final Object lifecycleLock = new Object();
    /** Lock order is lifecycleLock, writeOwnershipLock, then minuteLedgerLock. Never hold this during I/O. */
    private final Object writeOwnershipLock = new Object();
    private final ConcurrentHashMap<UUID, PlayerProfile> pendingProfiles = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<JoinRecord> pendingJoins = new ConcurrentLinkedQueue<>();
    private final long flushIntervalTicks;
    private final QueueScheduler scheduler;
    private final AtomicBoolean flushInProgress = new AtomicBoolean(false);
    private final AtomicBoolean immediateFlushScheduled = new AtomicBoolean(false);
    private final AtomicBoolean minuteCommitInProgress = new AtomicBoolean(false);
    private final AtomicLong minuteCommitGeneration = new AtomicLong();
    private final AtomicBoolean recoverySettlementPending = new AtomicBoolean();
    private volatile Runnable recoverySettlement = () -> { };
    private final Deque<WriteBatch> pendingRecoveryBatches = new ArrayDeque<>();
    private WriteBatch activeWriteBatch;
    private static final int MAX_BATCHES_PER_FLUSH = 16;

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
        synchronized (writeOwnershipLock) {
            return ownedMinuteDeltaLocked(uuid).toRangeTotals();
        }
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
        synchronized (writeOwnershipLock) {
            MinuteDelta total = new MinuteDelta(0, 0);
            for (MinuteDelta delta : pendingMinutes.values()) total = total.plus(delta);
            if (activeWriteBatch != null) {
                for (MinuteDelta delta : activeWriteBatch.minutes().values()) total = total.plus(delta);
            }
            for (WriteBatch batch : pendingRecoveryBatches) {
                for (MinuteDelta delta : batch.minutes().values()) total = total.plus(delta);
            }
            return total.toRangeTotals();
        }
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
        int processed = 0;
        while (processed < MAX_BATCHES_PER_FLUSH) {
            if (applyNextRecoveryBatch()) {
                processed++;
                continue;
            }
            WriteBatch batch = obtainActiveWriteBatch();
            if (batch == null || batch.isEmpty()) break;
            minuteCommitInProgress.set(true);
            try {
                repository.applyWriteBatch(batch);
            } finally {
                minuteCommitInProgress.set(false);
            }
            synchronized (writeOwnershipLock) {
                if (activeWriteBatch == batch) {
                    synchronized (minuteLedgerLock) {
                        confirmMinuteBatch(batch.minutes());
                    }
                    activeWriteBatch = null;
                    minuteCommitGeneration.incrementAndGet();
                }
            }
            counters.flushBatches.increment();
            processed++;
        }
        if (hasOutstandingWork()) {
            scheduleImmediateFlush();
        }
    }

    private boolean applyNextRecoveryBatch() throws Exception {
        WriteBatch recovery;
        synchronized (writeOwnershipLock) {
            recovery = pendingRecoveryBatches.peekFirst();
        }
        if (recovery == null) return false;
        repository.applyWriteBatch(recovery);
        boolean settled = false;
        synchronized (writeOwnershipLock) {
            if (pendingRecoveryBatches.peekFirst() == recovery) {
                pendingRecoveryBatches.removeFirst();
                settled = pendingRecoveryBatches.isEmpty();
            }
        }
        if (settled) settleRecoveryJournal();
        return true;
    }

    private WriteBatch obtainActiveWriteBatch() {
        synchronized (writeOwnershipLock) {
            if (activeWriteBatch != null) return activeWriteBatch;
            if (pendingMinutes.isEmpty() && pendingProfiles.isEmpty() && pendingJoins.isEmpty()) return null;
            Map<UUID, MinuteDelta> minutes = new ConcurrentHashMap<>(pendingMinutes);
            Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>(pendingProfiles);
            List<JoinRecord> joins = new ArrayList<>(pendingJoins);
            pendingMinutes.keySet().removeAll(minutes.keySet());
            pendingProfiles.keySet().removeAll(profiles.keySet());
            pendingJoins.removeAll(joins);
            activeWriteBatch = new WriteBatch(UUID.randomUUID(), Instant.now(), Map.copyOf(minutes), Map.copyOf(profiles), List.copyOf(joins));
            return activeWriteBatch;
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
        TransitionResult result = flushUntilSettled(timeoutSeconds);
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
        TransitionResult result = flushUntilSettled(timeoutSeconds);
        return result == TransitionResult.SUCCESS && hasOutstandingWork() ? TransitionResult.WRITE_FAILED : result;
    }

    public boolean isFlushInProgressForShutdown() {
        return flushInProgress.get();
    }

    public boolean hasOutstandingWorkForShutdown() {
        return hasOutstandingWork();
    }

    public void restoreRecoverySnapshot(RecoveryJournalSnapshot snapshot, Runnable onDurablySettled) {
        if (snapshot == null || snapshot.isEmpty()) return;
        synchronized (lifecycleLock) {
            if (state != QueueState.RUNNING) return;
            synchronized (writeOwnershipLock) {
                pendingRecoveryBatches.addAll(snapshot.batches());
                recoverySettlement = onDurablySettled == null ? () -> { } : onDurablySettled;
                recoverySettlementPending.set(true);
            }
        }
    }

    public void closeDatabaseAfterFlush(Runnable closeDatabase, int maxWaitSeconds,
                                        java.util.function.Consumer<RecoveryJournalSnapshot> persistRecovery) {
        RecoveryJournalSnapshot snapshot = recoverySnapshot();
        if (!snapshot.isEmpty()) persistRecovery.accept(snapshot);
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
                plugin.getLogger().severe("Playtime database left open because an in-flight queue flush did not settle.");
                return;
            }
            closeDatabase.run();
        });
    }

    public void closeDatabaseAfterFlush(Runnable closeDatabase, int maxWaitSeconds) {
        CompletableFuture.runAsync(() -> {
            long deadline = System.nanoTime() + Math.max(1, maxWaitSeconds) * 1_000_000_000L;
            while (flushInProgress.get() && System.nanoTime() < deadline) {
                try { Thread.sleep(WAIT_SLEEP_MILLIS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
            }
            if (flushInProgress.get()) {
                plugin.getLogger().severe("Playtime database left open because an in-flight queue flush did not settle.");
                return;
            }
            closeDatabase.run();
        });
    }

    private void settleRecoveryJournal() {
        if (recoverySettlementPending.get()
                && recoverySettlementPending.compareAndSet(true, false)) {
            recoverySettlement.run();
        }
    }

    public RecoveryJournalSnapshot recoverySnapshot() {
        synchronized (writeOwnershipLock) {
            List<WriteBatch> batches = new ArrayList<>(pendingRecoveryBatches);
            if (activeWriteBatch != null) batches.add(activeWriteBatch);
            if (!pendingMinutes.isEmpty() || !pendingProfiles.isEmpty() || !pendingJoins.isEmpty()) {
                Map<UUID, MinuteDelta> minutes = new ConcurrentHashMap<>(pendingMinutes);
                Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>(pendingProfiles);
                List<JoinRecord> joins = new ArrayList<>(pendingJoins);
                pendingMinutes.keySet().removeAll(minutes.keySet());
                pendingProfiles.keySet().removeAll(profiles.keySet());
                pendingJoins.removeAll(joins);
                batches.add(new WriteBatch(UUID.randomUUID(), Instant.now(), Map.copyOf(minutes), Map.copyOf(profiles), List.copyOf(joins)));
            }
            return new RecoveryJournalSnapshot(batches);
        }
    }

    public record RecoveryJournalSnapshot(List<WriteBatch> batches) {
        public RecoveryJournalSnapshot {
            batches = List.copyOf(batches);
        }
        public boolean isEmpty() {
            return batches.isEmpty();
        }
    }

    private boolean hasOutstandingWork() {
        synchronized (writeOwnershipLock) {
            return activeWriteBatch != null || !pendingRecoveryBatches.isEmpty()
                    || !pendingMinutes.isEmpty() || !pendingProfiles.isEmpty() || !pendingJoins.isEmpty();
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
            int batchMinutes = activeWriteBatch == null ? 0 : activeWriteBatch.minutes().size();
            int batchJoins = activeWriteBatch == null ? 0 : activeWriteBatch.joins().size();
            int batchProfiles = activeWriteBatch == null ? 0 : activeWriteBatch.profiles().size();
            for (WriteBatch recovery : pendingRecoveryBatches) {
                batchMinutes += recovery.minutes().size();
                batchJoins += recovery.joins().size();
                batchProfiles += recovery.profiles().size();
            }
            return new OutstandingWork(pendingMinutes.size() + batchMinutes,
                    pendingJoins.size() + batchJoins,
                    pendingProfiles.size() + batchProfiles);
        }
    }

    UUID activeBatchIdForTesting() {
        synchronized (writeOwnershipLock) { return activeWriteBatch == null ? null : activeWriteBatch.batchId(); }
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

    private MinuteDelta ownedMinuteDeltaLocked(UUID uuid) {
        MinuteDelta delta = pendingMinutes.get(uuid);
        if (activeWriteBatch != null) delta = plus(delta, activeWriteBatch.minutes().get(uuid));
        for (WriteBatch recovery : pendingRecoveryBatches) delta = plus(delta, recovery.minutes().get(uuid));
        return delta == null ? new MinuteDelta(0, 0) : delta;
    }

    private MinuteDelta plus(MinuteDelta left, MinuteDelta right) {
        return left == null ? right : right == null ? left : left.plus(right);
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

    private TransitionResult flushUntilSettled(int timeoutSeconds) {
        long deadline = System.nanoTime() + Math.max(1, timeoutSeconds) * 1_000_000_000L;
        TransitionResult result;
        do {
            result = flushSyncInternal();
            if (result != TransitionResult.SUCCESS || !hasOutstandingWork()) return result;
        } while (System.nanoTime() < deadline);
        return hasOutstandingWork() ? TransitionResult.WRITE_FAILED : TransitionResult.SUCCESS;
    }

    @Override
    public void close() {
        shutdown(10);
    }
}
