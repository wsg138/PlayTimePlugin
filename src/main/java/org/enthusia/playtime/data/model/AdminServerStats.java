package org.enthusia.playtime.data.model;

public final class AdminServerStats {

    public long playersWithPlaytime;
    public long totalMinutes;
    public long activeMinutes;
    public long afkMinutes;
    public long uniquePlayersJoined;
    public long totalJoins;
    public long newPlayers;
    public long returningPlayers;
    public long retainedNewPlayers;
    public long avgUniquePlayersPerDay;
    public long maxUniquePlayersPerDay;

    public void applyPending(RangeTotals pendingTotals) {
        this.activeMinutes += pendingTotals.activeMinutes;
        this.afkMinutes += pendingTotals.afkMinutes;
        this.totalMinutes += pendingTotals.totalMinutes;
    }
}
