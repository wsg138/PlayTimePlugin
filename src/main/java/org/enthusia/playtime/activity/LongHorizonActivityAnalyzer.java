package org.enthusia.playtime.activity;

import java.util.Deque;
import java.util.List;

/**
 * Coalesces accepted player-controlled input into one pulse per second and analyzes
 * a time-bounded horizon that cannot be evicted by high-rate raw event spam.
 *
 * <p>The purpose is deliberately conservative: a client that only emits occasional
 * keepalive input just often enough to avoid IDLE must not receive ACTIVE credit,
 * even if it randomizes timing, action type, movement, or camera rotation.</p>
 */
final class LongHorizonActivityAnalyzer {
    static final long RETENTION_MILLIS = 15L * 60L * 1000L;
    private static final long PULSE_BUCKET_MILLIS = 1_000L;
    private static final long MIN_SPARSE_SPAN_MILLIS = 75_000L;
    private static final int MIN_SPARSE_PULSES = 3;
    private static final double MAX_SPARSE_OCCUPANCY = 0.55D;
    private static final double SPARSE_KEEPALIVE_EVIDENCE = 0.99D;
    private static final long RECOVERY_DENSITY_WINDOW_MILLIS = 30_000L;
    private static final long RECOVERY_MAX_GAP_MILLIS = 5_000L;
    private static final double RECOVERY_MIN_OCCUPANCY = 0.70D;

    private LongHorizonActivityAnalyzer() {
    }

    static void recordPulse(Deque<Long> pulses, long timestampMillis) {
        long bucket = Math.floorDiv(timestampMillis, PULSE_BUCKET_MILLIS) * PULSE_BUCKET_MILLIS;
        Long last = pulses.peekLast();
        if (last == null || bucket > last) {
            pulses.addLast(bucket);
        }
        trim(pulses, timestampMillis);
    }

    static double sparseKeepaliveEvidence(List<Long> pulses, long nowMillis, long idleMillis) {
        if (pulses.size() < MIN_SPARSE_PULSES || idleMillis <= 0L) {
            return 0.0D;
        }
        Segment segment = latestContinuousSegment(pulses, nowMillis, idleMillis);
        if (segment == null || segment.count() < MIN_SPARSE_PULSES) {
            return 0.0D;
        }
        long minimumSpan = Math.max(MIN_SPARSE_SPAN_MILLIS, idleMillis + idleMillis / 4L);
        if (segment.spanMillis() < minimumSpan) {
            return 0.0D;
        }
        double occupiedSeconds = segment.count();
        double elapsedSeconds = Math.max(1.0D,
                segment.spanMillis() / (double) PULSE_BUCKET_MILLIS + 1.0D);
        return occupiedSeconds / elapsedSeconds < MAX_SPARSE_OCCUPANCY
                ? SPARSE_KEEPALIVE_EVIDENCE
                : 0.0D;
    }

    static boolean hasDenseRecentActivity(List<Long> pulses, long nowMillis) {
        long cutoff = nowMillis - RECOVERY_DENSITY_WINDOW_MILLIS;
        int count = 0;
        long first = Long.MIN_VALUE;
        long previous = Long.MIN_VALUE;
        for (long pulse : pulses) {
            if (pulse < cutoff || pulse > nowMillis) {
                continue;
            }
            if (first == Long.MIN_VALUE) {
                first = pulse;
            }
            if (previous != Long.MIN_VALUE && pulse - previous > RECOVERY_MAX_GAP_MILLIS) {
                return false;
            }
            previous = pulse;
            count++;
        }
        if (count == 0 || first > cutoff + RECOVERY_MAX_GAP_MILLIS) {
            return false;
        }
        double windowSeconds = RECOVERY_DENSITY_WINDOW_MILLIS / (double) PULSE_BUCKET_MILLIS;
        return count / windowSeconds >= RECOVERY_MIN_OCCUPANCY;
    }

    private static Segment latestContinuousSegment(List<Long> pulses, long nowMillis, long idleMillis) {
        int latestIndex = latestIndexAtOrBefore(pulses, nowMillis);
        if (latestIndex < 0) {
            return null;
        }
        long latest = pulses.get(latestIndex);
        if (nowMillis - latest >= idleMillis) {
            return null;
        }
        int startIndex = latestIndex;
        while (startIndex > 0) {
            long current = pulses.get(startIndex);
            long previous = pulses.get(startIndex - 1);
            if (current - previous >= idleMillis) {
                break;
            }
            startIndex--;
        }
        long first = pulses.get(startIndex);
        return new Segment(latestIndex - startIndex + 1, Math.max(0L, latest - first));
    }

    private static int latestIndexAtOrBefore(List<Long> pulses, long nowMillis) {
        for (int index = pulses.size() - 1; index >= 0; index--) {
            if (pulses.get(index) <= nowMillis) {
                return index;
            }
        }
        return -1;
    }

    private static void trim(Deque<Long> pulses, long nowMillis) {
        long cutoff = nowMillis - RETENTION_MILLIS;
        while (!pulses.isEmpty() && pulses.peekFirst() < cutoff) {
            pulses.removeFirst();
        }
    }

    private record Segment(int count, long spanMillis) {
    }
}
