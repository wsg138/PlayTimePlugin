package org.enthusia.playtime.leaderboard;

import org.enthusia.playtime.api.PlaytimeMetric;
import org.enthusia.playtime.api.PlaytimeRange;

import java.util.Objects;

public final class LeaderboardKey {

    public final PlaytimeMetric metric;
    public final PlaytimeRange range;

    public LeaderboardKey(PlaytimeMetric metric, PlaytimeRange range) {
        this.metric = metric;
        this.range = range;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LeaderboardKey that)) return false;
        return metric == that.metric && range == that.range;
    }

    @Override
    public int hashCode() {
        return Objects.hash(metric, range);
    }
}
