package org.enthusia.playtime.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.WriteBatch;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.RecoveryApplyResult;
import org.enthusia.playtime.data.PlaytimeRepository.JoinRecord;
import org.enthusia.playtime.data.WriteBatch.MinuteBucket;
import org.enthusia.playtime.data.model.MinuteDelta;
import org.enthusia.playtime.data.model.PlayerProfile;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Compact crash-recovery ownership record used only when shutdown cannot settle a closed queue. */
public final class ShutdownRecoveryJournal {
    private static final int FORMAT_VERSION = 4;
    private static final String UUID_KEY = "uuid";
    private static final String ACTIVE_KEY = "active";
    private static final String AFK_KEY = "afk";
    private static final String ACTIVE_FIELD_SUFFIX = ".active";
    private static final String AFK_FIELD_SUFFIX = ".afk";
    private static final String MINUTE_BUCKET_PATH = ".minuteBuckets[";
    private static final String MINUTES_FIELD_PREFIX = "minutes.";
    private final File file;

    public ShutdownRecoveryJournal(PlayTimePlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "shutdown-recovery.yml");
    }

    public File fileForLogging() {
        return file;
    }

    public void restoreInto(AsyncWriteQueue queue) {
        if (!file.isFile()) return;
        AsyncWriteQueue.RecoveryJournalSnapshot snapshot = read();
        queue.restoreRecoverySnapshot(snapshot, this::deleteAfterDurableFlush);
    }

    /**
     * Applies a retained journal before runtime-owned indexes and tier ledgers are created.
     * The journal remains untouched unless every exact-once batch is durably settled.
     */
    public int replaySynchronously(PlaytimeRepository repository) {
        if (!file.isFile()) {
            return 0;
        }
        AsyncWriteQueue.RecoveryJournalSnapshot snapshot = read();
        int settled = 0;
        try {
            for (WriteBatch batch : snapshot.batches()) {
                RecoveryApplyResult result = repository.applyWriteBatch(batch);
                if (result != RecoveryApplyResult.APPLIED
                        && result != RecoveryApplyResult.ALREADY_APPLIED) {
                    throw new IllegalStateException("Recovery repository returned no durable result");
                }
                settled++;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Failed to settle shutdown recovery journal; leaving it untouched for retry.",
                    failure);
        }
        deleteAfterDurableFlush();
        return settled;
    }

    public void write(AsyncWriteQueue.RecoveryJournalSnapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", FORMAT_VERSION);
        yaml.set("createdAt", Instant.now().toEpochMilli());
        List<Map<String, Object>> batches = snapshot.batches().stream()
                .map(this::serializeBatch)
                .toList();
        yaml.set("batches", batches);
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            yaml.save(temporary);
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupportedAtomicMove) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temporary.toPath());
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw new IllegalStateException("Failed to write shutdown recovery journal", failure);
        }
    }

    private Map<String, Object> serializeBatch(WriteBatch batch) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("batchId", batch.batchId().toString());
        entry.put("aggregationTime", batch.aggregationTime().toEpochMilli());
        Map<String, Object> minutes = new ConcurrentHashMap<>();
        batch.minutes().forEach((uuid, delta) -> minutes.put(uuid.toString(),
                Map.of(ACTIVE_KEY, delta.activeMinutes(), AFK_KEY, delta.afkMinutes())));
        entry.put("minutes", minutes);
        entry.put("minuteBuckets", batch.minuteBuckets().entrySet().stream()
                .map(this::serializeMinuteBucket)
                .toList());
        Map<String, Object> lastSeen = new ConcurrentHashMap<>();
        batch.lastSeen().forEach((uuid, instant) -> lastSeen.put(uuid.toString(), instant.toEpochMilli()));
        entry.put("lastSeen", lastSeen);
        Map<String, Object> profiles = new LinkedHashMap<>();
        batch.profiles().forEach((uuid, profile) -> profiles.put(uuid.toString(), serializeProfile(profile)));
        entry.put("profiles", profiles);
        entry.put("joins", batch.joins().stream().map(this::serializeJoin).toList());
        return entry;
    }

    private Map<String, Object> serializeMinuteBucket(Map.Entry<MinuteBucket, MinuteDelta> entry) {
        MinuteBucket bucket = entry.getKey();
        MinuteDelta delta = entry.getValue();
        return Map.of(UUID_KEY, bucket.uuid().toString(),
                "hourStart", bucket.hourStart().toEpochMilli(),
                ACTIVE_KEY, delta.activeMinutes(), AFK_KEY, delta.afkMinutes());
    }

    private Map<String, Object> serializeProfile(PlayerProfile profile) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("username", profile.username());
        values.put("displayName", profile.displayName());
        values.put("seenAt", profile.seenAt().toEpochMilli());
        return values;
    }

    private Map<String, Object> serializeJoin(JoinRecord join) {
        return Map.of(UUID_KEY, join.uuid().toString(), "joinedAt", join.joinedAt().toEpochMilli());
    }

    private AsyncWriteQueue.RecoveryJournalSnapshot read() {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception failure) {
            throw new IllegalStateException("Shutdown recovery journal is malformed; leaving it untouched for recovery: "
                    + file.getAbsolutePath(), failure);
        }

        int format = yaml.getInt("format", -1);
        Instant createdAt = Instant.ofEpochMilli(requiredNonNegativeLong(yaml.get("createdAt"), "createdAt"));
        List<WriteBatch> batches = new ArrayList<>();
        if (format == 2) {
            batches.add(readBatch(yaml, "", createdAt));
        } else if (format == 3 || format == FORMAT_VERSION) {
            List<?> rawBatches = yaml.getList("batches");
            if (rawBatches == null || rawBatches.isEmpty()) {
                throw invalid("Recovery journal has no batches");
            }
            for (int index = 0; index < rawBatches.size(); index++) {
                Object value = rawBatches.get(index);
                if (!(value instanceof Map<?, ?> entry)) {
                    throw invalid("Recovery batch " + index + " is not a map");
                }
                batches.add(readBatchMap(entry, createdAt, index));
            }
        } else {
            throw invalid("Unsupported shutdown recovery journal format " + format);
        }

        if (batches.stream().allMatch(WriteBatch::isEmpty)) {
            throw invalid("Recovery journal contains no recoverable writes");
        }
        return new AsyncWriteQueue.RecoveryJournalSnapshot(batches);
    }

    private WriteBatch readBatchMap(Map<?, ?> entry, Instant fallbackAggregationTime, int batchIndex) {
        String prefix = "batches[" + batchIndex + "]";
        Map<UUID, MinuteDelta> minutes = new ConcurrentHashMap<>();
        Object minuteObject = entry.get("minutes");
        if (minuteObject != null) {
            if (!(minuteObject instanceof Map<?, ?> entries)) {
                throw invalid(prefix + ".minutes is not a map");
            }
            for (Map.Entry<?, ?> minute : entries.entrySet()) {
                UUID uuid = requiredUuid(String.valueOf(minute.getKey()), prefix + ".minutes UUID");
                if (!(minute.getValue() instanceof Map<?, ?> values)) {
                    throw invalid(prefix + ".minutes." + uuid + " is not a map");
                }
                long active = requiredNonNegativeLong(values.get(ACTIVE_KEY), prefix + ".minutes." + uuid + ACTIVE_FIELD_SUFFIX);
                long afk = requiredNonNegativeLong(values.get(AFK_KEY), prefix + ".minutes." + uuid + AFK_FIELD_SUFFIX);
                minutes.put(uuid, new MinuteDelta(active, afk));
            }
        }

        Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();
        Object profileObject = entry.get("profiles");
        if (profileObject != null) {
            if (!(profileObject instanceof Map<?, ?> entries)) {
                throw invalid(prefix + ".profiles is not a map");
            }
            for (Map.Entry<?, ?> profile : entries.entrySet()) {
                UUID uuid = requiredUuid(String.valueOf(profile.getKey()), prefix + ".profiles UUID");
                if (!(profile.getValue() instanceof Map<?, ?> values)) {
                    throw invalid(prefix + ".profiles." + uuid + " is not a map");
                }
                profiles.put(uuid, new PlayerProfile(uuid, nullableString(values.get("username")),
                        nullableString(values.get("displayName")),
                        Instant.ofEpochMilli(requiredNonNegativeLong(values.get("seenAt"),
                                prefix + ".profiles." + uuid + ".seenAt"))));
            }
        }

        List<JoinRecord> joins = new ArrayList<>();
        Object joinObject = entry.get("joins");
        if (joinObject != null) {
            if (!(joinObject instanceof List<?> entries)) {
                throw invalid(prefix + ".joins is not a list");
            }
            for (int joinIndex = 0; joinIndex < entries.size(); joinIndex++) {
                Object value = entries.get(joinIndex);
                if (!(value instanceof Map<?, ?> join)) {
                    throw invalid(prefix + ".joins[" + joinIndex + "] is not a map");
                }
                UUID uuid = requiredUuid(nullableString(join.get(UUID_KEY)),
                        prefix + ".joins[" + joinIndex + "].uuid");
                joins.add(new JoinRecord(uuid, Instant.ofEpochMilli(requiredNonNegativeLong(join.get("joinedAt"),
                        prefix + ".joins[" + joinIndex + "].joinedAt"))));
            }
        }

        UUID batchId = requiredUuid(nullableString(entry.get("batchId")), prefix + ".batchId");
        Instant aggregationTime = entry.containsKey("aggregationTime")
                ? Instant.ofEpochMilli(requiredNonNegativeLong(entry.get("aggregationTime"), prefix + ".aggregationTime"))
                : fallbackAggregationTime;
        Map<MinuteBucket, MinuteDelta> minuteBuckets = new ConcurrentHashMap<>();
        Object bucketObject = entry.get("minuteBuckets");
        if (bucketObject != null) {
            if (!(bucketObject instanceof List<?> bucketEntries)) {
                throw invalid(prefix + ".minuteBuckets is not a list");
            }
            for (int bucketIndex = 0; bucketIndex < bucketEntries.size(); bucketIndex++) {
                Object value = bucketEntries.get(bucketIndex);
                if (!(value instanceof Map<?, ?> bucket)) {
                    throw invalid(prefix + MINUTE_BUCKET_PATH + bucketIndex + "] is not a map");
                }
                Map.Entry<MinuteBucket, MinuteDelta> parsed = readMinuteBucket(bucket, prefix, bucketIndex);
                minuteBuckets.merge(parsed.getKey(), parsed.getValue(), MinuteDelta::plus);
            }
        }
        Map<UUID, Instant> lastSeen = new ConcurrentHashMap<>();
        Object lastSeenObject = entry.get("lastSeen");
        if (lastSeenObject != null) {
            if (!(lastSeenObject instanceof Map<?, ?> entries)) {
                throw invalid(prefix + ".lastSeen is not a map");
            }
            for (Map.Entry<?, ?> seen : entries.entrySet()) {
                UUID uuid = requiredUuid(String.valueOf(seen.getKey()), prefix + ".lastSeen UUID");
                lastSeen.put(uuid, Instant.ofEpochMilli(requiredNonNegativeLong(seen.getValue(),
                        prefix + ".lastSeen." + uuid)));
            }
        }
        if (minuteBuckets.isEmpty() && lastSeen.isEmpty()) {
            return new WriteBatch(batchId, aggregationTime,
                    Map.copyOf(minutes), Map.copyOf(profiles), List.copyOf(joins));
        }
        return new WriteBatch(batchId, aggregationTime, Map.copyOf(minutes), Map.copyOf(profiles),
                List.copyOf(joins), Map.copyOf(minuteBuckets), Map.copyOf(lastSeen));
    }

    private Map.Entry<MinuteBucket, MinuteDelta> readMinuteBucket(Map<?, ?> bucket,
                                                                  String prefix, int bucketIndex) {
        String path = prefix + MINUTE_BUCKET_PATH + bucketIndex + "]";
        UUID uuid = requiredUuid(nullableString(bucket.get(UUID_KEY)), path + ".uuid");
        Instant hourStart = Instant.ofEpochMilli(requiredNonNegativeLong(bucket.get("hourStart"),
                path + ".hourStart"));
        long active = requiredNonNegativeLong(bucket.get(ACTIVE_KEY), path + ACTIVE_FIELD_SUFFIX);
        long afk = requiredNonNegativeLong(bucket.get(AFK_KEY), path + AFK_FIELD_SUFFIX);
        return Map.entry(new MinuteBucket(uuid, hourStart), new MinuteDelta(active, afk));
    }

    private WriteBatch readBatch(YamlConfiguration yaml, String prefix, Instant fallbackAggregationTime) {
        Map<UUID, MinuteDelta> minutes = new ConcurrentHashMap<>();
        ConfigurationSection minuteSection = optionalSection(yaml, prefix + "minutes");
        if (minuteSection != null) {
            for (String key : minuteSection.getKeys(false)) {
                UUID uuid = requiredUuid(key, prefix + "minutes UUID");
                long active = requiredNonNegativeLong(yaml.get(prefix + MINUTES_FIELD_PREFIX + key + ACTIVE_FIELD_SUFFIX),
                        prefix + MINUTES_FIELD_PREFIX + key + ACTIVE_FIELD_SUFFIX);
                long afk = requiredNonNegativeLong(yaml.get(prefix + MINUTES_FIELD_PREFIX + key + AFK_FIELD_SUFFIX),
                        prefix + MINUTES_FIELD_PREFIX + key + AFK_FIELD_SUFFIX);
                minutes.put(uuid, new MinuteDelta(active, afk));
            }
        }

        Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();
        ConfigurationSection profileSection = optionalSection(yaml, prefix + "profiles");
        if (profileSection != null) {
            for (String key : profileSection.getKeys(false)) {
                UUID uuid = requiredUuid(key, prefix + "profiles UUID");
                profiles.put(uuid, new PlayerProfile(uuid, yaml.getString(prefix + "profiles." + key + ".username"),
                        yaml.getString(prefix + "profiles." + key + ".displayName"),
                        Instant.ofEpochMilli(requiredNonNegativeLong(yaml.get(prefix + "profiles." + key + ".seenAt"),
                                prefix + "profiles." + key + ".seenAt"))));
            }
        }

        List<JoinRecord> joins = new ArrayList<>();
        Object joinValue = yaml.get(prefix + "joins");
        if (joinValue != null && !(joinValue instanceof List<?>)) {
            throw invalid(prefix + "joins is not a list");
        }
        List<?> rawJoins = joinValue instanceof List<?> list ? list : List.of();
        for (int index = 0; index < rawJoins.size(); index++) {
            Object value = rawJoins.get(index);
            if (!(value instanceof Map<?, ?> join)) {
                throw invalid(prefix + "joins[" + index + "] is not a map");
            }
            UUID uuid = requiredUuid(nullableString(join.get(UUID_KEY)), prefix + "joins[" + index + "].uuid");
            joins.add(new JoinRecord(uuid, Instant.ofEpochMilli(requiredNonNegativeLong(join.get("joinedAt"),
                    prefix + "joins[" + index + "].joinedAt"))));
        }
        UUID batchId = requiredUuid(yaml.getString(prefix + "batchId"), prefix + "batchId");
        Instant aggregationTime = yaml.contains(prefix + "aggregationTime")
                ? Instant.ofEpochMilli(requiredNonNegativeLong(yaml.get(prefix + "aggregationTime"),
                        prefix + "aggregationTime")) : fallbackAggregationTime;
        return new WriteBatch(batchId, aggregationTime,
                Map.copyOf(minutes), Map.copyOf(profiles), List.copyOf(joins));
    }

    private ConfigurationSection optionalSection(YamlConfiguration yaml, String path) {
        Object value = yaml.get(path);
        if (value == null) {
            return null;
        }
        ConfigurationSection section = yaml.getConfigurationSection(path);
        if (section == null) {
            throw invalid(path + " is not a map");
        }
        return section;
    }

    private UUID requiredUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (Exception failure) {
            throw invalid(field + " is missing or invalid");
        }
    }

    private long requiredLong(Object value, String field) {
        if (!(value instanceof Number number)) {
            throw invalid(field + " is missing or not numeric");
        }
        if ((number instanceof Float || number instanceof Double)
                && (!Double.isFinite(number.doubleValue()) || number.doubleValue() != Math.rint(number.doubleValue()))) {
            throw invalid(field + " is not an integer");
        }
        return number.longValue();
    }

    private long requiredNonNegativeLong(Object value, String field) {
        long result = requiredLong(value, field);
        if (result < 0L) {
            throw invalid(field + " is negative");
        }
        return result;
    }

    private String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private IllegalStateException invalid(String message) {
        return new IllegalStateException(message + "; leaving shutdown-recovery.yml untouched for manual recovery.");
    }

    private void deleteAfterDurableFlush() {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to delete settled shutdown recovery journal", failure);
        }
    }
}
