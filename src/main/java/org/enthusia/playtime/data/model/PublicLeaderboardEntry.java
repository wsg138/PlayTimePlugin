package org.enthusia.playtime.data.model;

import java.time.Instant;
import java.util.UUID;

public final class PublicLeaderboardEntry {

    public final int rank;
    public final UUID uuid;
    public final String username;
    public final String displayName;
    public final long activeMinutes;
    public final long afkMinutes;
    public final long totalMinutes;
    public final long value;
    public final Instant firstSeen;
    public final Instant lastSeen;
    public final Instant updatedAt;

    public PublicLeaderboardEntry(int rank,
                                  UUID uuid,
                                  String username,
                                  String displayName,
                                  long activeMinutes,
                                  long afkMinutes,
                                  long totalMinutes,
                                  long value,
                                  Instant firstSeen,
                                  Instant lastSeen,
                                  Instant updatedAt) {
        this.rank = rank;
        this.uuid = uuid;
        this.username = username;
        this.displayName = displayName;
        this.activeMinutes = activeMinutes;
        this.afkMinutes = afkMinutes;
        this.totalMinutes = totalMinutes;
        this.value = value;
        this.firstSeen = firstSeen;
        this.lastSeen = lastSeen;
        this.updatedAt = updatedAt;
    }
}
