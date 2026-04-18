package org.enthusia.playtime.data;

public enum SqlDialect {
    SQLITE,
    MYSQL;

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

    public String dailyAggIndexes() {
        return switch (this) {
            case SQLITE -> """
                CREATE INDEX IF NOT EXISTS idx_daily_agg_day_metric
                ON daily_agg (day, total_minutes, active_minutes, afk_minutes);
                """;
            case MYSQL -> "SELECT 1;";
        };
    }

    public String lifetimeAggIndexes() {
        return switch (this) {
            case SQLITE -> """
                CREATE INDEX IF NOT EXISTS idx_lifetime_agg_total
                ON lifetime_agg (total_minutes, active_minutes, afk_minutes);
                """;
            case MYSQL -> "SELECT 1;";
        };
    }

    public String joinsLogIndexes() {
        return switch (this) {
            case SQLITE -> """
                CREATE INDEX IF NOT EXISTS idx_joins_log_uuid_time
                ON joins_log (player_uuid, joined_at);
                """;
            case MYSQL -> "SELECT 1;";
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
                  afk_minutes = VALUES(afk_minutes),
                  total_minutes = VALUES(total_minutes);
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
