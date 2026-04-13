package org.enthusia.playtime.data.model;

public final class PlaytimeSnapshot {
    public final long activeMinutes;
    public final long afkMinutes;
    public final long totalMinutes;

    public PlaytimeSnapshot(long activeMinutes, long afkMinutes, long totalMinutes) {
        this.activeMinutes = activeMinutes;
        this.afkMinutes = afkMinutes;
        this.totalMinutes = totalMinutes;
    }
}
