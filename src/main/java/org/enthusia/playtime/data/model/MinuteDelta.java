package org.enthusia.playtime.data.model;

public final class MinuteDelta {

    private final long activeMinutes;
    private final long afkMinutes;

    public MinuteDelta(long activeMinutes, long afkMinutes) {
        this.activeMinutes = Math.max(0L, activeMinutes);
        this.afkMinutes = Math.max(0L, afkMinutes);
    }

    public long activeMinutes() {
        return activeMinutes;
    }

    public long afkMinutes() {
        return afkMinutes;
    }

    public long totalMinutes() {
        return activeMinutes + afkMinutes;
    }

    public MinuteDelta plus(MinuteDelta other) {
        return new MinuteDelta(this.activeMinutes + other.activeMinutes, this.afkMinutes + other.afkMinutes);
    }

    public RangeTotals toRangeTotals() {
        return new RangeTotals(activeMinutes, afkMinutes, totalMinutes());
    }
}
