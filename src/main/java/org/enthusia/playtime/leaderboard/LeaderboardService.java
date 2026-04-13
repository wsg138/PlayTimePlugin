package org.enthusia.playtime.leaderboard;

import org.enthusia.playtime.api.PlaytimeMetric;
import org.enthusia.playtime.api.PlaytimeRange;

import java.util.List;

public interface LeaderboardService {

    List<LeaderboardRow> getTop(PlaytimeMetric metric, PlaytimeRange range, int page, int pageSize);
}
