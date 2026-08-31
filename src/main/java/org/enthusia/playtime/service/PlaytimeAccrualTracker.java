package org.enthusia.playtime.service;

import org.enthusia.playtime.activity.ActivityState;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accumulates real connected time using a monotonic clock and converts completed
 * 60-second windows into timestamped playtime credits. Partial windows survive
 * reconnects and runtime handoffs.
 *
 * <p>The suspicious streak is intentionally an allowance consumed since the last
 * detector-approved recovery marker, not merely a count of adjacent suspicious
 * minutes. IDLE, AFK, reconnects, and ordinary ACTIVE observations therefore do
 * not replenish suspicious credit. The runtime also caps suspicious ACTIVE grace
 * at one minute so a detected automation cannot farm a long grace window.</p>
 */
final class PlaytimeAccrualTracker {
    static final long NANOS_PER_MINUTE = 60_000_000_000L;
    static final long DEFAULT_MAX_SAMPLE_NANOS = 5L * NANOS_PER_MINUTE;
    private static final long ZERO_NANOS = 0L;
    private static final int ZERO_STREAK = 0;
    private static final int NEXT_STREAK = 1;
    private static final int MAX_SUSPICIOUS_ACTIVE_GRACE_MINUTES = 1;
    private static final List<ActivityState> MINUTE_STATE_PRIORITY = List.of(
            ActivityState.ACTIVE, ActivityState.IDLE, ActivityState.AFK, ActivityState.SUSPICIOUS);

    private final int maxCountedSuspiciousMinutes;
    private final long maxSampleNanos;
    private final Map<UUID, PlayerAccrual> players = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    PlaytimeAccrualTracker(int maxCountedSuspiciousMinutes, Map<UUID, Snapshot> initialState) {
        this(maxCountedSuspiciousMinutes, DEFAULT_MAX_SAMPLE_NANOS, initialState);
    }

    PlaytimeAccrualTracker(int maxCountedSuspiciousMinutes, long maxSampleNanos,
                           Map<UUID, Snapshot> initialState) {
        this.maxCountedSuspiciousMinutes = Math.min(MAX_SUSPICIOUS_ACTIVE_GRACE_MINUTES,
                Math.max(ZERO_STREAK, maxCountedSuspiciousMinutes));
        this.maxSampleNanos = Math.max(NANOS_PER_MINUTE, maxSampleNanos);
        if (initialState != null) {
            initialState.forEach((uuid, snapshot) -> players.put(uuid, PlayerAccrual.from(snapshot)));
        }
    }

    void connect(UUID uuid, long nowNanos, long resetMarker) {
        connect(uuid, nowNanos, inferredInstant(nowNanos), resetMarker);
    }

    void connect(UUID uuid, long nowNanos, Instant now, long resetMarker) {
        synchronized (lock) {
            PlayerAccrual state = players.computeIfAbsent(uuid, ignored -> new PlayerAccrual());
            connectLocked(state, nowNanos, now, resetMarker);
        }
    }

    AccrualResult sample(UUID uuid, long nowNanos, ActivityState observedState,
                         long resetMarker) {
        return sample(uuid, nowNanos, inferredInstant(nowNanos), observedState, resetMarker);
    }

    AccrualResult sample(UUID uuid, long nowNanos, Instant now, ActivityState observedState,
                         long resetMarker) {
        Objects.requireNonNull(now, "now");
        synchronized (lock) {
            PlayerAccrual state = players.computeIfAbsent(uuid, ignored -> new PlayerAccrual());
            ActivityState normalizedState = observedState == null ? ActivityState.ACTIVE : observedState;
            if (!state.connected) {
                connectLocked(state, nowNanos, now, resetMarker);
                state.lastState = normalizedState;
                return AccrualResult.empty(state.lastState, state.suspiciousStreak);
            }
            ActivityState effectiveState = effectiveState(state, normalizedState, resetMarker);
            long elapsed = nowNanos - state.lastSampleNanos;
            state.lastSampleNanos = nowNanos;
            state.lastSampleInstant = now;
            state.lastState = effectiveState;
            if (elapsed <= ZERO_NANOS) {
                return AccrualResult.empty(effectiveState, state.suspiciousStreak);
            }
            long creditedNanos = Math.min(elapsed, maxSampleNanos);
            append(state, effectiveState, creditedNanos, now.minusNanos(creditedNanos));
            return drain(state, effectiveState);
        }
    }

    AccrualResult disconnect(UUID uuid, long nowNanos) {
        return disconnect(uuid, nowNanos, inferredInstant(nowNanos));
    }

    AccrualResult disconnect(UUID uuid, long nowNanos, Instant now) {
        Objects.requireNonNull(now, "now");
        synchronized (lock) {
            PlayerAccrual state = players.get(uuid);
            if (state == null || !state.connected) {
                return AccrualResult.empty(ActivityState.ACTIVE, ZERO_STREAK);
            }
            long elapsed = nowNanos - state.lastSampleNanos;
            state.lastSampleNanos = ZERO_NANOS;
            state.lastSampleInstant = now;
            state.connected = false;
            if (elapsed > ZERO_NANOS) {
                long creditedNanos = Math.min(elapsed, maxSampleNanos);
                append(state, state.lastState, creditedNanos, now.minusNanos(creditedNanos));
            }
            state.reconnectGuard = state.suspiciousStreak > ZERO_STREAK;
            return drain(state, state.lastState);
        }
    }

    Map<UUID, Snapshot> snapshot() {
        synchronized (lock) {
            Map<UUID, Snapshot> snapshot = new ConcurrentHashMap<>();
            players.forEach((uuid, state) -> snapshot.put(uuid, state.snapshot()));
            return Collections.unmodifiableMap(snapshot);
        }
    }

    boolean removeIfEmpty(UUID uuid) {
        synchronized (lock) {
            PlayerAccrual state = players.get(uuid);
            if (state != null && !state.connected && state.pendingNanos == ZERO_NANOS
                    && state.suspiciousStreak == ZERO_STREAK) {
                players.remove(uuid);
                return true;
            }
            return false;
        }
    }

    private void connectLocked(PlayerAccrual state, long nowNanos, Instant now, long resetMarker) {
        applyLegitimateReset(state, resetMarker);
        state.connected = true;
        state.lastSampleNanos = nowNanos;
        state.lastSampleInstant = Objects.requireNonNull(now, "now");
        state.reconnectGuard = state.suspiciousStreak > ZERO_STREAK;
    }

    private ActivityState effectiveState(PlayerAccrual state, ActivityState observedState,
                                         long resetMarker) {
        boolean legitimateReset = applyLegitimateReset(state, resetMarker);
        if (state.reconnectGuard && !legitimateReset) {
            return ActivityState.SUSPICIOUS;
        }
        return observedState;
    }

    private static boolean applyLegitimateReset(PlayerAccrual state, long resetMarker) {
        if (resetMarker <= state.processedResetMarker) {
            return false;
        }
        state.processedResetMarker = resetMarker;
        state.suspiciousStreak = ZERO_STREAK;
        state.reconnectGuard = false;
        return true;
    }

    private void append(PlayerAccrual state, ActivityState activityState, long nanos,
                        Instant intervalStart) {
        if (nanos <= ZERO_NANOS) {
            return;
        }
        Segment last = state.segments.peekLast();
        if (last != null && last.state == activityState && last.end().equals(intervalStart)) {
            last.nanos += nanos;
        } else {
            state.segments.addLast(new Segment(activityState, nanos, intervalStart));
        }
        state.pendingNanos += nanos;
    }

    private AccrualResult drain(PlayerAccrual state, ActivityState effectiveState) {
        int active = ZERO_STREAK;
        int afk = ZERO_STREAK;
        int completed = ZERO_STREAK;
        boolean thresholdCrossed = false;
        List<MinuteCredit> credits = new ArrayList<>();
        while (state.pendingNanos >= NANOS_PER_MINUTE) {
            ConsumedMinute minute = consumeMinute(state);
            if (minute == null) {
                break;
            }
            completed++;
            int activeCredit = 0;
            int afkCredit = 0;
            switch (minute.state()) {
                case ACTIVE -> {
                    active++;
                    activeCredit = 1;
                }
                case IDLE, AFK -> {
                    afk++;
                    afkCredit = 1;
                }
                case SUSPICIOUS -> {
                    state.suspiciousStreak++;
                    if (state.suspiciousStreak <= maxCountedSuspiciousMinutes) {
                        active++;
                        activeCredit = 1;
                    } else {
                        afk++;
                        afkCredit = 1;
                        if (state.suspiciousStreak == maxCountedSuspiciousMinutes + NEXT_STREAK) {
                            thresholdCrossed = true;
                        }
                    }
                }
                default -> throw new IllegalStateException("Unsupported activity state " + minute.state());
            }
            credits.add(new MinuteCredit(minute.creditedAt(), activeCredit, afkCredit));
        }
        return new AccrualResult(active, afk, completed, effectiveState,
                state.suspiciousStreak, thresholdCrossed, credits);
    }

    private ConsumedMinute consumeMinute(PlayerAccrual state) {
        long remaining = NANOS_PER_MINUTE;
        EnumMap<ActivityState, Long> durations = new EnumMap<>(ActivityState.class);
        Instant creditedAt = null;
        while (remaining > ZERO_NANOS) {
            Segment segment = state.segments.peekFirst();
            if (segment == null) {
                // A malformed restored snapshot must not cancel the repeating tick
                // or abort quit/shutdown persistence. Drop only the unowned drift.
                state.pendingNanos = ZERO_NANOS;
                return null;
            }
            long consumed = Math.min(remaining, segment.nanos);
            durations.merge(segment.state, consumed, Long::sum);
            segment.start = segment.start.plusNanos(consumed);
            creditedAt = segment.start;
            segment.nanos -= consumed;
            state.pendingNanos -= consumed;
            remaining -= consumed;
            if (segment.nanos == ZERO_NANOS) {
                state.segments.removeFirst();
            }
        }
        ActivityState selected = ActivityState.ACTIVE;
        long selectedNanos = -NEXT_STREAK;
        for (ActivityState candidate : MINUTE_STATE_PRIORITY) {
            long duration = durations.getOrDefault(candidate, ZERO_NANOS);
            if (duration > selectedNanos
                    || (duration == selectedNanos && candidate == ActivityState.SUSPICIOUS)) {
                selected = candidate;
                selectedNanos = duration;
            }
        }
        return new ConsumedMinute(selected, Objects.requireNonNull(creditedAt));
    }

    private static Instant inferredInstant(long nowNanos) {
        return Instant.EPOCH.plusNanos(Math.max(ZERO_NANOS, nowNanos));
    }

    record AccrualResult(int activeMinutes, int afkMinutes, int completedMinutes,
                         ActivityState state, int suspiciousStreak, boolean thresholdCrossed,
                         List<MinuteCredit> credits) {
        AccrualResult {
            credits = List.copyOf(credits);
        }

        static AccrualResult empty(ActivityState state, int suspiciousStreak) {
            return new AccrualResult(ZERO_STREAK, ZERO_STREAK, ZERO_STREAK,
                    state, suspiciousStreak, false, List.of());
        }

        List<MinuteCredit> reallocate(int requestedActiveMinutes, int requestedAfkMinutes) {
            int[] activeAllocation = new int[credits.size()];
            int[] afkAllocation = new int[credits.size()];
            boolean[] used = new boolean[credits.size()];
            AllocationRemainder remainder = preserveExistingTypes(
                    Math.max(0, requestedActiveMinutes), Math.max(0, requestedAfkMinutes),
                    activeAllocation, afkAllocation, used);
            int activeRemaining = assignUnused(remainder.activeMinutes(), activeAllocation, used);
            int afkRemaining = assignUnused(remainder.afkMinutes(), afkAllocation, used);
            return collectAllocations(activeAllocation, afkAllocation, activeRemaining, afkRemaining);
        }

        private AllocationRemainder preserveExistingTypes(int requestedActive, int requestedAfk,
                                                          int[] activeAllocation, int[] afkAllocation,
                                                          boolean[] used) {
            int activeRemaining = requestedActive;
            int afkRemaining = requestedAfk;
            for (int index = 0; index < credits.size(); index++) {
                MinuteCredit credit = credits.get(index);
                if (credit.activeMinutes() > 0 && activeRemaining > 0) {
                    activeAllocation[index] = 1;
                    activeRemaining--;
                    used[index] = true;
                    continue;
                }
                if (credit.afkMinutes() > 0 && afkRemaining > 0) {
                    afkAllocation[index] = 1;
                    afkRemaining--;
                    used[index] = true;
                }
            }
            return new AllocationRemainder(activeRemaining, afkRemaining);
        }

        private int assignUnused(int requested, int[] allocation, boolean[] used) {
            int remaining = requested;
            for (int index = 0; index < credits.size() && remaining > 0; index++) {
                if (!used[index]) {
                    allocation[index] = 1;
                    remaining--;
                    used[index] = true;
                }
            }
            return remaining;
        }

        private List<MinuteCredit> collectAllocations(int[] activeAllocation, int[] afkAllocation,
                                                      int activeRemaining, int afkRemaining) {
            List<MinuteCredit> allocated = new ArrayList<>();
            for (int index = 0; index < credits.size(); index++) {
                if (activeAllocation[index] > 0 || afkAllocation[index] > 0) {
                    allocated.add(new MinuteCredit(credits.get(index).creditedAt(),
                            activeAllocation[index], afkAllocation[index]));
                }
            }
            if (activeRemaining > 0 || afkRemaining > 0) {
                Instant creditedAt = credits.isEmpty()
                        ? Instant.now()
                        : credits.get(credits.size() - 1).creditedAt();
                allocated.add(new MinuteCredit(creditedAt, activeRemaining, afkRemaining));
            }
            return List.copyOf(allocated);
        }

        private record AllocationRemainder(int activeMinutes, int afkMinutes) {
        }
    }

    record MinuteCredit(Instant creditedAt, int activeMinutes, int afkMinutes) {
        MinuteCredit {
            creditedAt = Objects.requireNonNull(creditedAt, "creditedAt");
            activeMinutes = Math.max(0, activeMinutes);
            afkMinutes = Math.max(0, afkMinutes);
        }
    }

    record Snapshot(boolean connected, long lastSampleNanos, Instant lastSampleInstant,
                    ActivityState lastState, long pendingNanos, List<SegmentSnapshot> segments,
                    int suspiciousStreak, long processedResetMarker, boolean reconnectGuard) {
        Snapshot {
            segments = List.copyOf(segments);
        }
    }

    record SegmentSnapshot(ActivityState state, long nanos, Instant start) {
    }

    private record ConsumedMinute(ActivityState state, Instant creditedAt) {
    }

    private static final class PlayerAccrual {
        private boolean connected;
        private long lastSampleNanos;
        private Instant lastSampleInstant = Instant.EPOCH;
        private ActivityState lastState = ActivityState.ACTIVE;
        private long pendingNanos;
        private final Deque<Segment> segments = new ArrayDeque<>();
        private int suspiciousStreak;
        private long processedResetMarker;
        private boolean reconnectGuard;

        private static PlayerAccrual from(Snapshot snapshot) {
            PlayerAccrual state = new PlayerAccrual();
            state.connected = snapshot.connected();
            state.lastSampleNanos = snapshot.lastSampleNanos();
            state.lastSampleInstant = snapshot.lastSampleInstant();
            state.lastState = snapshot.lastState();
            for (SegmentSnapshot segment : snapshot.segments()) {
                if (segment.nanos() > ZERO_NANOS) {
                    state.segments.addLast(new Segment(segment.state(), segment.nanos(), segment.start()));
                    state.pendingNanos += segment.nanos();
                }
            }
            state.suspiciousStreak = snapshot.suspiciousStreak();
            state.processedResetMarker = snapshot.processedResetMarker();
            state.reconnectGuard = snapshot.reconnectGuard();
            return state;
        }

        private Snapshot snapshot() {
            List<SegmentSnapshot> segmentSnapshots = new ArrayList<>(segments.size());
            for (Segment segment : segments) {
                segmentSnapshots.add(new SegmentSnapshot(segment.state, segment.nanos, segment.start));
            }
            return new Snapshot(connected, lastSampleNanos, lastSampleInstant, lastState, pendingNanos,
                    segmentSnapshots, suspiciousStreak, processedResetMarker, reconnectGuard);
        }
    }

    private static final class Segment {
        private final ActivityState state;
        private long nanos;
        private Instant start;

        private Segment(ActivityState state, long nanos, Instant start) {
            this.state = Objects.requireNonNull(state, "state");
            this.nanos = nanos;
            this.start = Objects.requireNonNull(start, "start");
        }

        private Instant end() {
            return start.plusNanos(nanos);
        }
    }
}
