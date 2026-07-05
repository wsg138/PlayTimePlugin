package org.enthusia.playtime.data.model;

public final class MinuteDelta {

    private final long activeMinuteCount;
    private final long afkMinuteCount;

    public MinuteDelta(long activeMinutes, long afkMinutes) {
        this.activeMinuteCount = Math.max(0L, activeMinutes);
        this.afkMinuteCount = Math.max(0L, afkMinutes);
    }

    public long activeMinutes() {
        return activeMinuteCount;
    }

    public long afkMinutes() {
        return afkMinuteCount;
    }

    public long totalMinutes() {
        return activeMinuteCount + afkMinuteCount;
    }

    public MinuteDelta plus(MinuteDelta other) {
        return new MinuteDelta(this.activeMinuteCount + other.activeMinuteCount, this.afkMinuteCount + other.afkMinuteCount);
    }

    public RangeTotals toRangeTotals() {
        return new RangeTotals(activeMinuteCount, afkMinuteCount, totalMinutes());
    }
}
