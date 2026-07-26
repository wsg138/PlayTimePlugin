package org.enthusia.playtime.data;

public enum SqlDialect {
    SQLITE,
    MYSQL;

    private static final String NOOP_QUERY = "SELECT 1;";

    public static SqlDialect fromStorageType(StorageType type) {
        return switch (type) {
            case SQLITE -> SQLITE;
            case MYSQL -> MYSQL;
        };
    }

    public String dailyAggCreateTable() {
        return switch (this) {
            case SQLITE -> """
                CREATE TABLE IF NOT EXISTS daily_agg (
                  player_uuid TEXT NOT NULL,
                  day DATE NOT NULL,
                  active_minutes INTEGER NOT NULL DEFAULT 0,
                  afk_minutes INTEGER NOT NULL DEFAULT 0,
                  total_minutes INTEGER NOT NULL DEFAULT 0,
                  PRIMARY KEY (player_uuid, day)
                );
                """;
            case MYSQL -> """
                CREATE TABLE IF NOT EXISTS daily_agg (
                  player_uuid CHAR(36) NOT NULL,
                  day DATE NOT NULL,
                  active_minutes INT NOT NULL DEFAULT 0,
                  afk_minutes INT NOT NULL DEFAULT 0,
                  total_minutes INT NOT NULL DEFAULT 0,
                  PRIMARY KEY (player_uuid, day)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """;
        };
    }

    public String lifetimeAggCreateTable() {
        return switch (this) {
            case SQLITE -> """
                CREATE TABLE IF NOT EXISTS lifetime_agg (
                  player_uuid TEXT PRIMARY KEY,
                  first_join TIMESTAMP NOT NULL,
                  last_join TIMESTAMP NOT NULL,
                  last_seen TIMESTAMP NOT NULL,
                  active_minutes INTEGER NOT NULL DEFAULT 0,
                  afk_minutes INTEGER NOT NULL DEFAULT 0,
                  total_minutes INTEGER NOT NULL DEFAULT 0
                );
                """;
            case MYSQL -> """
                CREATE TABLE IF NOT EXISTS lifetime_agg (
                  player_uuid CHAR(36) NOT NULL,
                  first_join TIMESTAMP NOT NULL,
                  last_join TIMESTAMP NOT NULL,
                  last_seen TIMESTAMP NOT NULL,
                  active_minutes INT NOT NULL DEFAULT 0,
                  afk_minutes INT NOT NULL DEFAULT 0,
                  total_minutes INT NOT NULL DEFAULT 0,
                  PRIMARY KEY (player_uuid)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """;
        };
    }

    public String joinsLogCreateTable() {
        return switch (this) {
            case SQLITE -> """
                CREATE TABLE IF NOT EXISTS joins_log (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  player_uuid TEXT NOT NULL,
                  joined_at TIMESTAMP NOT NULL
                );
                """;
            case MYSQL -> """
                CREATE TABLE IF NOT EXISTS joins_log (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  player_uuid CHAR(36) NOT NULL,
                  joined_at TIMESTAMP NOT NULL,
                  PRIMARY KEY (id),
                  INDEX idx_joins_uuid_time (player_uuid, joined_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """;
        };
    }

    public String hourlyAggCreateTable() {
        return switch (this) {
            case SQLITE -> """
                CREATE TABLE IF NOT EXISTS hourly_agg (
                  player_uuid TEXT NOT NULL,
                  hour_start TIMESTAMP NOT NULL,
                  active_minutes INTEGER NOT NULL DEFAULT 0,
                  afk_minutes INTEGER NOT NULL DEFAULT 0,
                  total_minutes INTEGER NOT NULL DEFAULT 0,
                  PRIMARY KEY (player_uuid, hour_start)
                );
                """;
            case MYSQL -> """
                CREATE TABLE IF NOT EXISTS hourly_agg (
                  player_uuid CHAR(36) NOT NULL,
                  hour_start TIMESTAMP NOT NULL,
                  active_minutes INT NOT NULL DEFAULT 0,
                  afk_minutes INT NOT NULL DEFAULT 0,
                  total_minutes INT NOT NULL DEFAULT 0,
                  PRIMARY KEY (player_uuid, hour_start),
                  INDEX idx_hourly_agg_hour_metric (hour_start, total_minutes, active_minutes, afk_minutes)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """;
        };
    }

    public String playerProfilesCreateTable() {
        return switch (this) {
            case SQLITE -> """
                CREATE TABLE IF NOT EXISTS player_profiles (
                  player_uuid TEXT PRIMARY KEY,
                  username TEXT NOT NULL,
                  display_name TEXT,
                  first_seen TIMESTAMP NOT NULL,
                  last_seen TIMESTAMP NOT NULL,
                  updated_at TIMESTAMP NOT NULL
                );
                """;
            case MYSQL -> """
                CREATE TABLE IF NOT EXISTS player_profiles (
                  player_uuid CHAR(36) NOT NULL,
                  username VARCHAR(16) NOT NULL,
                  display_name VARCHAR(64) NULL,
                  first_seen TIMESTAMP NOT NULL,
                  last_seen TIMESTAMP NOT NULL,
                  updated_at TIMESTAMP NOT NULL,
                  PRIMARY KEY (player_uuid),
                  INDEX idx_player_profiles_username (username)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """;
        };
    }

    public String playerSkinProfilesCreateTable() {
        return switch (this) {
            case SQLITE -> """
                CREATE TABLE IF NOT EXISTS player_skin_profiles (
                  player_uuid TEXT PRIMARY KEY,
                  texture_value TEXT,
                  texture_signature TEXT,
                  last_known_name TEXT,
                  updated_at TIMESTAMP NOT NULL
                );
                """;
            case MYSQL -> """
                CREATE TABLE IF NOT EXISTS player_skin_profiles (
                  player_uuid CHAR(36) NOT NULL,
                  texture_value TEXT NULL,
                  texture_signature TEXT NULL,
                  last_known_name VARCHAR(16) NULL,
                  updated_at TIMESTAMP NOT NULL,
                  PRIMARY KEY (player_uuid),
                  INDEX idx_player_skin_profiles_name (last_known_name)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """;
        };
    }

    public String appliedBatchesCreateTable() {
        return switch (this) {
            case SQLITE -> "CREATE TABLE IF NOT EXISTS playtime_applied_batches (batch_id TEXT PRIMARY KEY, applied_at TIMESTAMP NOT NULL);";
            case MYSQL -> "CREATE TABLE IF NOT EXISTS playtime_applied_batches (batch_id VARCHAR(36) NOT NULL, applied_at TIMESTAMP NOT NULL, PRIMARY KEY (batch_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";
        };
    }

    public String recoveryBatchInsert() {
        return switch (this) {
            case SQLITE -> "INSERT OR IGNORE INTO playtime_applied_batches (batch_id, applied_at) VALUES (?, ?)";
            case MYSQL -> "INSERT IGNORE INTO playtime_applied_batches (batch_id, applied_at) VALUES (?, ?)";
        };
    }

    public String dailyAggIndexes() {
        return switch (this) {
            case SQLITE -> """
                CREATE INDEX IF NOT EXISTS idx_daily_agg_day_metric
                ON daily_agg (day, total_minutes, active_minutes, afk_minutes);
                """;
            case MYSQL -> NOOP_QUERY;
        };
    }

    public String lifetimeAggIndexes() {
        return switch (this) {
            case SQLITE -> """
                CREATE INDEX IF NOT EXISTS idx_lifetime_agg_total
                ON lifetime_agg (total_minutes, active_minutes, afk_minutes);
                """;
            case MYSQL -> NOOP_QUERY;
        };
    }

    public String hourlyAggIndexes() {
        return switch (this) {
            case SQLITE -> """
                CREATE INDEX IF NOT EXISTS idx_hourly_agg_hour_metric
                ON hourly_agg (hour_start, total_minutes, active_minutes, afk_minutes);
                """;
            case MYSQL -> NOOP_QUERY;
        };
    }

    public String joinsLogIndexes() {
        return switch (this) {
            case SQLITE -> """
                CREATE INDEX IF NOT EXISTS idx_joins_log_uuid_time
                ON joins_log (player_uuid, joined_at);
                """;
            case MYSQL -> NOOP_QUERY;
        };
    }

    public String playerProfilesIndexes() {
        return switch (this) {
            case SQLITE -> """
                CREATE INDEX IF NOT EXISTS idx_player_profiles_username
                ON player_profiles (username);
                """;
            case MYSQL -> NOOP_QUERY;
        };
    }


    public String dailyAggUpsert() {
        // params: player_uuid, day, active, afk, total
        return switch (this) {
            case SQLITE -> """
                INSERT INTO daily_agg (player_uuid, day, active_minutes, afk_minutes, total_minutes)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(player_uuid, day) DO UPDATE SET
                  active_minutes = active_minutes + excluded.active_minutes,
                  afk_minutes = afk_minutes + excluded.afk_minutes,
                  total_minutes = total_minutes + excluded.total_minutes;
                """;
            case MYSQL -> """
                INSERT INTO daily_agg (player_uuid, day, active_minutes, afk_minutes, total_minutes)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  active_minutes = active_minutes + VALUES(active_minutes),
                  afk_minutes = afk_minutes + VALUES(afk_minutes),
                  total_minutes = total_minutes + VALUES(total_minutes);
                """;
        };
    }

    public String lifetimeJoinUpsert() {
        // params: player_uuid, first_join, last_join, last_seen
        return switch (this) {
            case SQLITE -> """
                INSERT INTO lifetime_agg (player_uuid, first_join, last_join, last_seen, active_minutes, afk_minutes, total_minutes)
                VALUES (?, ?, ?, ?, 0, 0, 0)
                ON CONFLICT(player_uuid) DO UPDATE SET
                  last_join = excluded.last_join,
                  last_seen = excluded.last_seen;
                """;
            case MYSQL -> """
                INSERT INTO lifetime_agg (player_uuid, first_join, last_join, last_seen, active_minutes, afk_minutes, total_minutes)
                VALUES (?, ?, ?, ?, 0, 0, 0)
                ON DUPLICATE KEY UPDATE
                  last_join = VALUES(last_join),
                  last_seen = VALUES(last_seen);
                """;
        };
    }

    public String hourlyAggUpsert() {
        // params: player_uuid, hour_start, active, afk, total
        return switch (this) {
            case SQLITE -> """
                INSERT INTO hourly_agg (player_uuid, hour_start, active_minutes, afk_minutes, total_minutes)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(player_uuid, hour_start) DO UPDATE SET
                  active_minutes = active_minutes + excluded.active_minutes,
                  afk_minutes = afk_minutes + excluded.afk_minutes,
                  total_minutes = total_minutes + excluded.total_minutes;
                """;
            case MYSQL -> """
                INSERT INTO hourly_agg (player_uuid, hour_start, active_minutes, afk_minutes, total_minutes)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  active_minutes = active_minutes + VALUES(active_minutes),
                  afk_minutes = afk_minutes + VALUES(afk_minutes),
                  total_minutes = total_minutes + VALUES(total_minutes);
                """;
        };
    }

    public String lifetimeMinutesUpsert() {
        // params: player_uuid, active, afk, total
        return switch (this) {
            case SQLITE -> """
                INSERT INTO lifetime_agg (player_uuid, first_join, last_join, last_seen, active_minutes, afk_minutes, total_minutes)
                VALUES (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                  active_minutes = active_minutes + excluded.active_minutes,
                  afk_minutes = afk_minutes + excluded.afk_minutes,
                  total_minutes = total_minutes + excluded.total_minutes;
                """;
            case MYSQL -> """
                INSERT INTO lifetime_agg (player_uuid, first_join, last_join, last_seen, active_minutes, afk_minutes, total_minutes)
                VALUES (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  active_minutes = active_minutes + VALUES(active_minutes),
                  afk_minutes = afk_minutes + VALUES(afk_minutes),
                  total_minutes = total_minutes + VALUES(total_minutes);
                """;
        };
    }

    public String playerProfileUpsert() {
        // params: player_uuid, username, display_name, first_seen, last_seen, updated_at
        return switch (this) {
            case SQLITE -> """
                INSERT INTO player_profiles (player_uuid, username, display_name, first_seen, last_seen, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                  username = excluded.username,
                  display_name = excluded.display_name,
                  last_seen = excluded.last_seen,
                  updated_at = excluded.updated_at;
                """;
            case MYSQL -> """
                INSERT INTO player_profiles (player_uuid, username, display_name, first_seen, last_seen, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  username = VALUES(username),
                  display_name = VALUES(display_name),
                  last_seen = VALUES(last_seen),
                  updated_at = VALUES(updated_at);
                """;
        };
    }

    public String playerSkinProfileUpsert() {
        // params: player_uuid, texture_value, texture_signature, last_known_name, updated_at
        return switch (this) {
            case SQLITE -> """
                INSERT INTO player_skin_profiles (player_uuid, texture_value, texture_signature, last_known_name, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                  texture_value = excluded.texture_value,
                  texture_signature = excluded.texture_signature,
                  last_known_name = excluded.last_known_name,
                  updated_at = excluded.updated_at;
                """;
            case MYSQL -> """
                INSERT INTO player_skin_profiles (player_uuid, texture_value, texture_signature, last_known_name, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  texture_value = VALUES(texture_value),
                  texture_signature = VALUES(texture_signature),
                  last_known_name = VALUES(last_known_name),
                  updated_at = VALUES(updated_at);
                """;
        };
    }

    public String lifetimeAggAddLastSeenColumn() {
        return switch (this) {
            case SQLITE -> "ALTER TABLE lifetime_agg ADD COLUMN last_seen TIMESTAMP";
            case MYSQL -> "ALTER TABLE lifetime_agg ADD COLUMN last_seen TIMESTAMP NULL";
        };
    }

    public String lifetimeAggBackfillLastSeenColumn() {
        return "UPDATE lifetime_agg SET last_seen = last_join WHERE last_seen IS NULL";
    }
}
