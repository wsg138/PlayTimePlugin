package org.enthusia.playtime.leaderboard;

import java.util.UUID;

public final class LeaderboardRow {
    public final int rank;
    public final UUID uuid;
    public final String name;
    public final long minutes;

    public LeaderboardRow(int rank, UUID uuid, String name, long minutes) {
        this.rank = rank;
        this.uuid = uuid;
        this.name = name;
        this.minutes = minutes;
    }
}
