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

    /**
     * True only when the detector currently trusts the player as strongly active.
     * IDLE, AFK and SUSPICIOUS all return false.
     */
    default boolean isGenuinelyActive(UUID uuid) {
        return getLiveState(uuid) == ActivityState.ACTIVE;
    }

    /**
     * True for states that AFK-sensitive gameplay should normally treat as inactive.
     * SUSPICIOUS means untrusted/repetitive activity, not a cheating conviction.
     */
    default boolean isEffectivelyAfk(UUID uuid) {
        ActivityState state = getLiveState(uuid);
        return state == ActivityState.AFK || state == ActivityState.SUSPICIOUS;
    }
}
