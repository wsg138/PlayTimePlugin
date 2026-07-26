package org.enthusia.playtime.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.playtime.PlayTimePlugin;
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
    private static final int FORMAT_VERSION = 1;
    private final File file;

    public ShutdownRecoveryJournal(PlayTimePlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "shutdown-recovery.yml");
    }

    public void restoreInto(AsyncWriteQueue queue) {
        if (!file.isFile()) return;
        AsyncWriteQueue.RecoverySnapshot snapshot = read();
        queue.restoreRecoverySnapshot(snapshot, this::deleteAfterDurableFlush);
    }

    public void write(AsyncWriteQueue.RecoverySnapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", FORMAT_VERSION);
        yaml.set("createdAt", Instant.now().toEpochMilli());
        snapshot.minutes().forEach((uuid, delta) -> {
            String path = "minutes." + uuid;
            yaml.set(path + ".active", delta.activeMinutes());
            yaml.set(path + ".afk", delta.afkMinutes());
        });
        snapshot.profiles().forEach((uuid, profile) -> {
            String path = "profiles." + uuid;
            yaml.set(path + ".username", profile.username());
            yaml.set(path + ".displayName", profile.displayName());
            yaml.set(path + ".seenAt", profile.seenAt().toEpochMilli());
        });
        List<Map<String, Object>> joins = new ArrayList<>();
        for (JoinRecord join : snapshot.joins()) {
            joins.add(Map.of("uuid", join.uuid().toString(), "joinedAt", join.joinedAt().toEpochMilli()));
        }
        yaml.set("joins", joins);
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

    private AsyncWriteQueue.RecoverySnapshot read() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Map<UUID, MinuteDelta> minutes = new LinkedHashMap<>();
        if (yaml.isConfigurationSection("minutes")) {
            for (String key : yaml.getConfigurationSection("minutes").getKeys(false)) {
                UUID uuid = parse(key);
                if (uuid != null) minutes.put(uuid, new MinuteDelta(yaml.getLong("minutes." + key + ".active"),
                        yaml.getLong("minutes." + key + ".afk")));
            }
        }
        Map<UUID, PlayerProfile> profiles = new LinkedHashMap<>();
        if (yaml.isConfigurationSection("profiles")) {
            for (String key : yaml.getConfigurationSection("profiles").getKeys(false)) {
                UUID uuid = parse(key);
                if (uuid != null) profiles.put(uuid, new PlayerProfile(uuid, yaml.getString("profiles." + key + ".username"),
                        yaml.getString("profiles." + key + ".displayName"), Instant.ofEpochMilli(yaml.getLong("profiles." + key + ".seenAt"))));
            }
        }
        List<JoinRecord> joins = new ArrayList<>();
        for (Map<?, ?> join : yaml.getMapList("joins")) {
            UUID uuid = parse(String.valueOf(join.get("uuid")));
            Object millis = join.get("joinedAt");
            if (uuid != null && millis instanceof Number number) joins.add(new JoinRecord(uuid, Instant.ofEpochMilli(number.longValue())));
        }
        return new AsyncWriteQueue.RecoverySnapshot(minutes, profiles, joins);
    }

    private UUID parse(String value) {
        try { return UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; }
    }

    private void deleteAfterDurableFlush() {
        try { Files.deleteIfExists(file.toPath()); }
        catch (IOException failure) { throw new IllegalStateException("Failed to delete settled shutdown recovery journal", failure); }
    }
}
