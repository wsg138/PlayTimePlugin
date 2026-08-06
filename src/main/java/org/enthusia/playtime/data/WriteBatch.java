package org.enthusia.playtime.data;

import org.enthusia.playtime.data.PlaytimeRepository.JoinRecord;
import org.enthusia.playtime.data.model.MinuteDelta;
import org.enthusia.playtime.data.model.PlayerProfile;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable ownership unit for every queued SQL write and its recovery journal. */
public record WriteBatch(UUID batchId,
                         Instant aggregationTime,
                         Map<UUID, MinuteDelta> minutes,
                         Map<UUID, PlayerProfile> profiles,
                         List<JoinRecord> joins,
                         Map<MinuteBucket, MinuteDelta> minuteBuckets,
                         Map<UUID, Instant> lastSeen) {

    public WriteBatch {
        minutes = Map.copyOf(minutes);
        profiles = Map.copyOf(profiles);
        joins = List.copyOf(joins);
        minuteBuckets = Map.copyOf(minuteBuckets);
        lastSeen = Map.copyOf(lastSeen);
    }

    /** Backward-compatible constructor used by older tests and format-2/3 journals. */
    public WriteBatch(UUID batchId, Instant aggregationTime, Map<UUID, MinuteDelta> minutes,
                      Map<UUID, PlayerProfile> profiles, List<JoinRecord> joins) {
        this(batchId, aggregationTime, minutes, profiles, joins,
                bucketsAt(aggregationTime, minutes), Map.of());
    }

    public boolean isEmpty() {
        return minutes.isEmpty() && profiles.isEmpty() && joins.isEmpty()
                && minuteBuckets.isEmpty() && lastSeen.isEmpty();
    }

    private static Map<MinuteBucket, MinuteDelta> bucketsAt(Instant instant,
                                                             Map<UUID, MinuteDelta> minutes) {
        if (minutes.isEmpty()) {
            return Map.of();
        }
        Instant hour = instant.truncatedTo(ChronoUnit.HOURS);
        Map<MinuteBucket, MinuteDelta> buckets = new ConcurrentHashMap<>();
        minutes.forEach((uuid, delta) -> buckets.put(new MinuteBucket(uuid, hour), delta));
        return Map.copyOf(buckets);
    }

    public record MinuteBucket(UUID uuid, Instant hourStart) {
        public MinuteBucket {
            hourStart = hourStart.truncatedTo(ChronoUnit.HOURS);
        }
    }
}
