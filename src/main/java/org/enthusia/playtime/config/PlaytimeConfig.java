package org.enthusia.playtime.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.StorageType;

import java.util.List;

public final class PlaytimeConfig {

    private final PlayTimePlugin plugin;
    private final FileConfiguration cfg;

    public PlaytimeConfig(PlayTimePlugin plugin) {
        this.plugin = plugin;
        this.cfg = plugin.getConfig();
    }

    public StorageType getStorageType() {
        String raw = cfg.getString("storage.type", "sqlite").toLowerCase();
        return switch (raw) {
            case "mysql", "mariadb" -> StorageType.MYSQL;
            default -> StorageType.SQLITE;
        };
    }

    public String getSqliteFile() {
        return cfg.getString("storage.sqlite.file", "playtime.db");
    }

    public String getMysqlHost() {
        return cfg.getString("storage.mysql.host", "localhost");
    }

    public int getMysqlPort() {
        return cfg.getInt("storage.mysql.port", 3306);
    }

    public String getMysqlDatabase() {
        return cfg.getString("storage.mysql.database", "playtime");
    }

    public String getMysqlUsername() {
        return cfg.getString("storage.mysql.username", "root");
    }

    public String getMysqlPassword() {
        return cfg.getString("storage.mysql.password", "password");
    }

    public boolean isMysqlUseSsl() {
        return cfg.getBoolean("storage.mysql.use-ssl", false);
    }

    public int getMysqlPoolSize() {
        return cfg.getInt("storage.mysql.pool-size", 10);
    }

    public long getFlushIntervalTicks() {
        return cfg.getLong("storage.flush-interval-ticks", 1200L);
    }

    public int getIdleSeconds() {
        return cfg.getInt("sampling.idle-seconds", 60);
    }

    public int getAfkSeconds() {
        return cfg.getInt("sampling.afk-seconds", 300);
    }

    public boolean countChatAsActivity() {
        return cfg.getBoolean("chat.count-chat-as-activity", true);
    }

    public boolean countCommandsAsActivity() {
        return cfg.getBoolean("chat.count-commands-as-activity", true);
    }

    public int getJoinRetentionDays() {
        return cfg.getInt("joins.retention-days", 30);
    }

    public String getJoinTimezoneId() {
        return cfg.getString("joins.timezone", "America/New_York");
    }

    public boolean isFirstJoinEnabled() {
        return cfg.getBoolean("joins.first-join.enabled", true);
    }

    public String getFirstJoinBroadcast() {
        return cfg.getString("joins.first-join.broadcast",
                "&a+ &f%player% &7just joined for the first time! Make them feel welcome.");
    }

    public List<String> getFirstJoinPlayerLines() {
        List<String> lines = cfg.getStringList("joins.first-join.player-message");
        if (lines == null || lines.isEmpty()) {
            return List.of(
                    "&aWelcome to Enthusia, %player%!",
                    "&7Use &b/tutorial &7for a quick start or ask in chat."
            );
        }
        return lines;
    }

    public boolean isFirstJoinPingEnabled() {
        return cfg.getBoolean("joins.first-join.ping.enabled", true);
    }

    public String getFirstJoinPingSound() {
        return cfg.getString("joins.first-join.ping.sound", "BLOCK_NOTE_BLOCK_PLING");
    }

    public float getFirstJoinPingVolume() {
        return (float) cfg.getDouble("joins.first-join.ping.volume", 1.25D);
    }

    public float getFirstJoinPingPitch() {
        return (float) cfg.getDouble("joins.first-join.ping.pitch", 1.6D);
    }

    public boolean isGuiEnabled() {
        return cfg.getBoolean("gui.enabled", true);
    }

    public boolean isPlaceholdersEnabled() {
        return cfg.getBoolean("placeholders.enabled", true);
    }

    public boolean isDebugEnabled() {
        return cfg.getBoolean("debug.enabled", false);
    }
}
