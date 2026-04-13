package org.enthusia.playtime.data.model;

import java.util.UUID;

/**
 * Represents a single row in a playtime leaderboard.
 */
public final class LeaderboardEntry {

    public final UUID uuid;
    public final long activeMinutes;
    public final long afkMinutes;
    public final long totalMinutes;
    public final int rank; // 1-based rank within the current page

    public LeaderboardEntry(UUID uuid,
                            long activeMinutes,
                            long afkMinutes,
                            long totalMinutes,
                            int rank) {
        this.uuid = uuid;
        this.activeMinutes = activeMinutes;
        this.afkMinutes = afkMinutes;
        this.totalMinutes = totalMinutes;
        this.rank = rank;
    }
}
