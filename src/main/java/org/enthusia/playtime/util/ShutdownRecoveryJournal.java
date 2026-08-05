package org.enthusia.playtime.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.WriteBatch;
import org.enthusia.playtime.data.PlaytimeRepository.JoinRecord;
import org.enthusia.playtime.data.model.MinuteDelta;
import org.enthusia.playtime.data.model.PlayerProfile;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Compact crash-recovery ownership record used only when shutdown cannot settle a closed queue. */
public final class ShutdownRecoveryJournal {
    private static final int FORMAT_VERSION = 3;
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

    public void write(AsyncWriteQueue.RecoveryJournalSnapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", FORMAT_VERSION);
        yaml.set("createdAt", Instant.now().toEpochMilli());
        List<Map<String, Object>> batches = new ArrayList<>();
        for (WriteBatch batch : snapshot.batches()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("batchId", batch.batchId().toString());
            entry.put("aggregationTime", batch.aggregationTime().toEpochMilli());
            Map<String, Object> minutes = new LinkedHashMap<>();
            batch.minutes().forEach((uuid, delta) -> minutes.put(uuid.toString(),
                    Map.of("active", delta.activeMinutes(), "afk", delta.afkMinutes())));
            entry.put("minutes", minutes);
            Map<String, Object> profiles = new LinkedHashMap<>();
            batch.profiles().forEach((uuid, profile) -> {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("username", profile.username());
                values.put("displayName", profile.displayName());
                values.put("seenAt", profile.seenAt().toEpochMilli());
                profiles.put(uuid.toString(), values);
            });
            entry.put("profiles", profiles);
            List<Map<String, Object>> joins = new ArrayList<>();
            for (JoinRecord join : batch.joins()) {
                joins.add(Map.of("uuid", join.uuid().toString(), "joinedAt", join.joinedAt().toEpochMilli()));
            }
            entry.put("joins", joins);
            batches.add(entry);
        }
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
        } else if (format == FORMAT_VERSION) {
            List<?> rawBatches = yaml.getList("batches");
            if (rawBatches == null || rawBatches.isEmpty()) {
                throw invalid("Format 3 recovery journal has no batches");
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
        Map<UUID, MinuteDelta> minutes = new LinkedHashMap<>();
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
                long active = requiredNonNegativeLong(values.get("active"), prefix + ".minutes." + uuid + ".active");
                long afk = requiredNonNegativeLong(values.get("afk"), prefix + ".minutes." + uuid + ".afk");
                minutes.put(uuid, new MinuteDelta(active, afk));
            }
        }

        Map<UUID, PlayerProfile> profiles = new LinkedHashMap<>();
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
                UUID uuid = requiredUuid(nullableString(join.get("uuid")),
                        prefix + ".joins[" + joinIndex + "].uuid");
                joins.add(new JoinRecord(uuid, Instant.ofEpochMilli(requiredNonNegativeLong(join.get("joinedAt"),
                        prefix + ".joins[" + joinIndex + "].joinedAt"))));
            }
        }

        UUID batchId = requiredUuid(nullableString(entry.get("batchId")), prefix + ".batchId");
        Instant aggregationTime = entry.containsKey("aggregationTime")
                ? Instant.ofEpochMilli(requiredNonNegativeLong(entry.get("aggregationTime"), prefix + ".aggregationTime"))
                : fallbackAggregationTime;
        return new WriteBatch(batchId, aggregationTime,
                Map.copyOf(minutes), Map.copyOf(profiles), List.copyOf(joins));
    }

    private WriteBatch readBatch(YamlConfiguration yaml, String prefix, Instant fallbackAggregationTime) {
        Map<UUID, MinuteDelta> minutes = new LinkedHashMap<>();
        ConfigurationSection minuteSection = optionalSection(yaml, prefix + "minutes");
        if (minuteSection != null) {
            for (String key : minuteSection.getKeys(false)) {
                UUID uuid = requiredUuid(key, prefix + "minutes UUID");
                long active = requiredNonNegativeLong(yaml.get(prefix + "minutes." + key + ".active"),
                        prefix + "minutes." + key + ".active");
                long afk = requiredNonNegativeLong(yaml.get(prefix + "minutes." + key + ".afk"),
                        prefix + "minutes." + key + ".afk");
                minutes.put(uuid, new MinuteDelta(active, afk));
            }
        }

        Map<UUID, PlayerProfile> profiles = new LinkedHashMap<>();
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
            UUID uuid = requiredUuid(nullableString(join.get("uuid")), prefix + "joins[" + index + "].uuid");
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
