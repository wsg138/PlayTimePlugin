package org.enthusia.playtime.activity;

import java.util.Deque;
import java.util.Iterator;

/**
 * Coalesces accepted player-controlled input into one pulse per second and analyzes
 * a time-bounded horizon that cannot be evicted by high-rate raw event spam.
 *
 * <p>The purpose is deliberately conservative: a client that only emits occasional
 * keepalive input just often enough to avoid IDLE must not receive ACTIVE credit,
 * even if it randomizes timing, action type, movement, or camera rotation. Ordinary
 * gameplay pauses of a few seconds are not sparse-keepalive evidence by themselves.</p>
 */
final class LongHorizonActivityAnalyzer {
    static final long RETENTION_MILLIS = 15L * 60L * 1000L;
    private static final long PULSE_BUCKET_MILLIS = 1_000L;
    private static final long MIN_SPARSE_SPAN_MILLIS = 75_000L;
    private static final int MIN_SPARSE_PULSES = 3;
    private static final double NEAR_IDLE_AVERAGE_GAP_RATIO = 1.0D / 3.0D;
    private static final long VERY_SPARSE_MIN_SPAN_MILLIS = 180_000L;
    private static final double VERY_SPARSE_MAX_OCCUPANCY = 0.08D;
    private static final double NEAR_IDLE_KEEPALIVE_EVIDENCE = 0.99D;
    private static final double VERY_SPARSE_KEEPALIVE_EVIDENCE = 0.90D;
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

    static double sparseKeepaliveEvidence(Deque<Long> pulses, long nowMillis, long idleMillis) {
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

        double averageGapMillis = segment.count() <= 1
                ? 0.0D
                : segment.spanMillis() / (double) (segment.count() - 1);
        if (averageGapMillis >= idleMillis * NEAR_IDLE_AVERAGE_GAP_RATIO) {
            return NEAR_IDLE_KEEPALIVE_EVIDENCE;
        }

        if (segment.spanMillis() < VERY_SPARSE_MIN_SPAN_MILLIS) {
            return 0.0D;
        }
        double elapsedSeconds = Math.max(1.0D,
                segment.spanMillis() / (double) PULSE_BUCKET_MILLIS + 1.0D);
        double occupancy = segment.count() / elapsedSeconds;
        return occupancy < VERY_SPARSE_MAX_OCCUPANCY
                ? VERY_SPARSE_KEEPALIVE_EVIDENCE
                : 0.0D;
    }

    static boolean hasDenseRecentActivity(Deque<Long> pulses, long nowMillis) {
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

    private static Segment latestContinuousSegment(Deque<Long> pulses, long nowMillis, long idleMillis) {
        Iterator<Long> descending = pulses.descendingIterator();
        long latest = Long.MIN_VALUE;
        long previous = Long.MIN_VALUE;
        long first = Long.MIN_VALUE;
        int count = 0;
        while (descending.hasNext()) {
            long pulse = descending.next();
            if (pulse > nowMillis) {
                continue;
            }
            if (latest == Long.MIN_VALUE) {
                latest = pulse;
                previous = pulse;
                first = pulse;
                count = 1;
                if (nowMillis - latest >= idleMillis) {
                    return null;
                }
                continue;
            }
            if (previous - pulse >= idleMillis) {
                break;
            }
            previous = pulse;
            first = pulse;
            count++;
        }
        return latest == Long.MIN_VALUE
                ? null
                : new Segment(count, Math.max(0L, latest - first));
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
