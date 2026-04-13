package org.enthusia.playtime.api;

import org.enthusia.playtime.activity.ActivityState;
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.data.model.RangeTotals;

import java.util.Optional;
import java.util.UUID;

public interface PlaytimeService {

    Optional<PlaytimeSnapshot> getLifetime(UUID uuid);

    RangeTotals getRangeTotals(UUID uuid, PlaytimeRange range);

    ActivityState getLiveState(UUID uuid);

    long getCurrentSessionMillis(UUID uuid);
}
