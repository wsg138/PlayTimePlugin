package org.enthusia.playtime.leaderboard;

import org.enthusia.playtime.api.PlaytimeMetric;
import org.enthusia.playtime.api.PlaytimeRange;

import java.util.Collections;
import java.util.List;

public final class LeaderboardServiceImpl implements LeaderboardService {

    @Override
    public List<LeaderboardRow> getTop(PlaytimeMetric metric,
                                       PlaytimeRange range,
                                       int page,
                                       int pageSize) {
        // TODO: query DB + cache
        return Collections.emptyList();
    }
}
