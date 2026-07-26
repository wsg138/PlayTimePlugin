package org.enthusia.playtime.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.WriteBatch;
import org.enthusia.playtime.data.PlaytimeRepository.JoinRecord;
import org.enthusia.playtime.data.model.MinuteDelta;
import org.enthusia.playtime.data.model.PlayerProfile;

import java.io.File;
import java.io.IOException;
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
            batch.minutes().forEach((uuid, delta) -> minutes.put(uuid.toString(), Map.of("active", delta.activeMinutes(), "afk", delta.afkMinutes())));
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
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
            yaml.save(temporary);
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to write shutdown recovery journal", failure);
        }
    }

    private AsyncWriteQueue.RecoveryJournalSnapshot read() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (yaml.getInt("format") == 2) return new AsyncWriteQueue.RecoveryJournalSnapshot(List.of(readBatch(yaml, "", Instant.ofEpochMilli(yaml.getLong("createdAt")))));
        List<WriteBatch> batches = new ArrayList<>();
        for (Map<?, ?> entry : yaml.getMapList("batches")) {
            batches.add(readBatchMap(entry, Instant.ofEpochMilli(yaml.getLong("createdAt"))));
        }
        return new AsyncWriteQueue.RecoveryJournalSnapshot(batches);
    }

    private WriteBatch readBatchMap(Map<?, ?> entry, Instant fallbackAggregationTime) {
        Map<UUID, MinuteDelta> minutes = new LinkedHashMap<>();
        if (entry.get("minutes") instanceof Map<?, ?> entries) {
            for (Map.Entry<?, ?> minute : entries.entrySet()) {
                UUID uuid = parse(String.valueOf(minute.getKey()));
                if (uuid != null && minute.getValue() instanceof Map<?, ?> values) {
                    minutes.put(uuid, new MinuteDelta(numberValue(values.get("active"), 0L), numberValue(values.get("afk"), 0L)));
                }
            }
        }
        Map<UUID, PlayerProfile> profiles = new LinkedHashMap<>();
        if (entry.get("profiles") instanceof Map<?, ?> entries) {
            for (Map.Entry<?, ?> profile : entries.entrySet()) {
                UUID uuid = parse(String.valueOf(profile.getKey()));
                if (uuid != null && profile.getValue() instanceof Map<?, ?> values) {
                    profiles.put(uuid, new PlayerProfile(uuid, nullableString(values.get("username")),
                            nullableString(values.get("displayName")), Instant.ofEpochMilli(numberValue(values.get("seenAt"), 0L))));
                }
            }
        }
        List<JoinRecord> joins = new ArrayList<>();
        if (entry.get("joins") instanceof List<?> entries) {
            for (Object value : entries) {
                if (value instanceof Map<?, ?> join) {
                    UUID uuid = parse(nullableString(join.get("uuid")));
                    if (uuid != null) joins.add(new JoinRecord(uuid, Instant.ofEpochMilli(numberValue(join.get("joinedAt"), 0L))));
                }
            }
        }
        UUID batchId = parse(nullableString(entry.get("batchId")));
        Instant aggregationTime = entry.containsKey("aggregationTime")
                ? Instant.ofEpochMilli(numberValue(entry.get("aggregationTime"), fallbackAggregationTime.toEpochMilli())) : fallbackAggregationTime;
        return new WriteBatch(batchId == null ? UUID.randomUUID() : batchId, aggregationTime,
                Map.copyOf(minutes), Map.copyOf(profiles), List.copyOf(joins));
    }

    private WriteBatch readBatch(YamlConfiguration yaml, String prefix, Instant fallbackAggregationTime) {
        Map<UUID, MinuteDelta> minutes = new LinkedHashMap<>();
        if (yaml.isConfigurationSection(prefix + "minutes")) {
            for (String key : yaml.getConfigurationSection(prefix + "minutes").getKeys(false)) {
                UUID uuid = parse(key);
                if (uuid != null) minutes.put(uuid, new MinuteDelta(yaml.getLong(prefix + "minutes." + key + ".active"),
                        yaml.getLong(prefix + "minutes." + key + ".afk")));
            }
        }
        Map<UUID, PlayerProfile> profiles = new LinkedHashMap<>();
        if (yaml.isConfigurationSection(prefix + "profiles")) {
            for (String key : yaml.getConfigurationSection(prefix + "profiles").getKeys(false)) {
                UUID uuid = parse(key);
                if (uuid != null) profiles.put(uuid, new PlayerProfile(uuid, yaml.getString(prefix + "profiles." + key + ".username"),
                        yaml.getString(prefix + "profiles." + key + ".displayName"), Instant.ofEpochMilli(yaml.getLong(prefix + "profiles." + key + ".seenAt"))));
            }
        }
        List<JoinRecord> joins = new ArrayList<>();
        for (Map<?, ?> join : yaml.getMapList(prefix + "joins")) {
            UUID uuid = parse(String.valueOf(join.get("uuid")));
            Object millis = join.get("joinedAt");
            if (uuid != null && millis instanceof Number number) joins.add(new JoinRecord(uuid, Instant.ofEpochMilli(number.longValue())));
        }
        UUID batchId = parse(yaml.getString(prefix + "batchId"));
        Instant aggregationTime = yaml.contains(prefix + "aggregationTime")
                ? Instant.ofEpochMilli(yaml.getLong(prefix + "aggregationTime")) : fallbackAggregationTime;
        return new WriteBatch(batchId == null ? UUID.randomUUID() : batchId, aggregationTime,
                Map.copyOf(minutes), Map.copyOf(profiles), List.copyOf(joins));
    }

    private UUID parse(String value) {
        try { return value == null ? null : UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; }
    }

    private long numberValue(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void deleteAfterDurableFlush() {
        try { Files.deleteIfExists(file.toPath()); }
        catch (IOException failure) { throw new IllegalStateException("Failed to delete settled shutdown recovery journal", failure); }
    }
}
