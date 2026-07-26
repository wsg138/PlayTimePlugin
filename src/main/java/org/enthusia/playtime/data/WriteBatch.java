package org.enthusia.playtime.data;

import org.enthusia.playtime.data.PlaytimeRepository.JoinRecord;
import org.enthusia.playtime.data.model.MinuteDelta;
import org.enthusia.playtime.data.model.PlayerProfile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable ownership unit for every queued SQL write and its recovery journal. */
public record WriteBatch(UUID batchId, Map<UUID, MinuteDelta> minutes, Map<UUID, PlayerProfile> profiles,
                         List<JoinRecord> joins) {
    public boolean isEmpty() {
        return minutes.isEmpty() && profiles.isEmpty() && joins.isEmpty();
    }
}
