package org.enthusia.playtime.data;

import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.enthusia.playtime.data.model.AdminServerStats;
import org.enthusia.playtime.data.model.LeaderboardEntry;
import org.enthusia.playtime.data.model.MinuteDelta;
import org.enthusia.playtime.data.model.PlayerProfile;
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.data.model.PublicLeaderboardEntry;
import org.enthusia.playtime.data.model.RangeTotals;
import org.enthusia.playtime.data.model.RecentJoinActivity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;

public final class PlaytimeRepository {

    private final JavaPlugin plugin;
    private final DatabaseProvider provider;
    private final SqlDialect dialect;
    private final ZoneId joinZoneId;

    public PlaytimeRepository(JavaPlugin plugin, DatabaseProvider provider, PlaytimeConfig config) {
        this.plugin = plugin;
        this.provider = provider;
        this.dialect = provider.getDialect();
        this.joinZoneId = config.joins().zoneId();
    }

    public void initSchema() throws SQLException {
        withSqliteRetry(() -> {
            try (Connection connection = provider.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute(dialect.dailyAggCreateTable());
                statement.execute(dialect.hourlyAggCreateTable());
                statement.execute(dialect.lifetimeAggCreateTable());
                statement.execute(dialect.joinsLogCreateTable());
                statement.execute(dialect.playerProfilesCreateTable());
                statement.execute(dialect.dailyAggIndexes());
                statement.execute(dialect.hourlyAggIndexes());
                statement.execute(dialect.lifetimeAggIndexes());
                statement.execute(dialect.joinsLogIndexes());
                statement.execute(dialect.playerProfilesIndexes());
                tryAddLastSeenColumn(statement);
            }
            return null;
        });
    }

    public boolean hasLifetimeRecord(UUID uuid) {
        String sql = "SELECT 1 FROM lifetime_agg WHERE player_uuid = ?";
        try (Connection connection = provider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to check known player state: " + exception.getMessage());
            return false;
        }
    }

    public int countKnownPlayers() {
        String sql = "SELECT COUNT(*) AS c FROM lifetime_agg";
        try (Connection connection = provider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getInt("c") : 0;
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to count known players: " + exception.getMessage());
            return 0;
        }
    }

    public void batchRecordJoins(List<JoinRecord> records) throws SQLException {
        if (records == null || records.isEmpty()) {
            return;
        }

        withSqliteRetry(() -> {
            try (Connection connection = provider.getConnection()) {
                connection.setAutoCommit(false);
                try (PreparedStatement joinStatement = connection.prepareStatement(
                        "INSERT INTO joins_log (player_uuid, joined_at) VALUES (?, ?)");
                     PreparedStatement lifetimeStatement = connection.prepareStatement(dialect.lifetimeJoinUpsert())) {
                    for (JoinRecord record : records) {
                        Timestamp timestamp = Timestamp.from(record.joinedAt());

                        joinStatement.setString(1, record.uuid().toString());
                        joinStatement.setTimestamp(2, timestamp);
                        joinStatement.addBatch();

                        lifetimeStatement.setString(1, record.uuid().toString());
                        lifetimeStatement.setTimestamp(2, timestamp);
                        lifetimeStatement.setTimestamp(3, timestamp);
                        lifetimeStatement.setTimestamp(4, timestamp);
                        lifetimeStatement.addBatch();
                    }

                    joinStatement.executeBatch();
                    lifetimeStatement.executeBatch();
                }
                connection.commit();
            }
            return null;
        });
    }

    public void batchRecordMinutes(Map<UUID, MinuteDelta> deltas, Instant instant) throws SQLException {
        if (deltas == null || deltas.isEmpty()) {
            return;
        }

        LocalDate day = LocalDate.ofInstant(instant, ZoneOffset.UTC);
        String dayString = day.toString();

        withSqliteRetry(() -> {
            try (Connection connection = provider.getConnection()) {
                connection.setAutoCommit(false);
                Timestamp hourStart = Timestamp.from(instant.truncatedTo(ChronoUnit.HOURS));
                try (PreparedStatement dailyStatement = connection.prepareStatement(dialect.dailyAggUpsert());
                     PreparedStatement hourlyStatement = connection.prepareStatement(dialect.hourlyAggUpsert());
                     PreparedStatement lifetimeStatement = connection.prepareStatement(dialect.lifetimeMinutesUpsert())) {
                    for (Map.Entry<UUID, MinuteDelta> entry : deltas.entrySet()) {
                        MinuteDelta delta = entry.getValue();
                        if (delta.totalMinutes() <= 0L) {
                            continue;
                        }

                        dailyStatement.setString(1, entry.getKey().toString());
                        dailyStatement.setString(2, dayString);
                        dailyStatement.setLong(3, delta.activeMinutes());
                        dailyStatement.setLong(4, delta.afkMinutes());
                        dailyStatement.setLong(5, delta.totalMinutes());
                        dailyStatement.addBatch();

                        hourlyStatement.setString(1, entry.getKey().toString());
                        hourlyStatement.setTimestamp(2, hourStart);
                        hourlyStatement.setLong(3, delta.activeMinutes());
                        hourlyStatement.setLong(4, delta.afkMinutes());
                        hourlyStatement.setLong(5, delta.totalMinutes());
                        hourlyStatement.addBatch();

                        lifetimeStatement.setString(1, entry.getKey().toString());
                        lifetimeStatement.setLong(2, delta.activeMinutes());
                        lifetimeStatement.setLong(3, delta.afkMinutes());
                        lifetimeStatement.setLong(4, delta.totalMinutes());
                        lifetimeStatement.addBatch();
                    }

                    dailyStatement.executeBatch();
                    hourlyStatement.executeBatch();
                    lifetimeStatement.executeBatch();
                }
                connection.commit();
            }
            return null;
        });
    }

    public void batchUpsertPlayerProfiles(List<PlayerProfile> profiles) throws SQLException {
        if (profiles == null || profiles.isEmpty()) {
            return;
        }

        withSqliteRetry(() -> {
            try (Connection connection = provider.getConnection();
                 PreparedStatement statement = connection.prepareStatement(dialect.playerProfileUpsert())) {
                for (PlayerProfile profile : profiles) {
                    if (profile.uuid() == null || profile.username() == null || profile.username().isBlank()) {
                        continue;
                    }
                    Timestamp seenAt = Timestamp.from(profile.seenAt());
                    statement.setString(1, profile.uuid().toString());
                    statement.setString(2, profile.username());
                    statement.setString(3, blankToNull(profile.displayName()));
                    statement.setTimestamp(4, seenAt);
                    statement.setTimestamp(5, seenAt);
                    statement.setTimestamp(6, Timestamp.from(Instant.now()));
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
    }

    public Optional<PlaytimeSnapshot> getLifetime(UUID uuid) {
        String sql = "SELECT active_minutes, afk_minutes, total_minutes FROM lifetime_agg WHERE player_uuid = ?";
        try (Connection connection = provider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PlaytimeSnapshot(
                        resultSet.getLong("active_minutes"),
                        resultSet.getLong("afk_minutes"),
                        resultSet.getLong("total_minutes")
                ));
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to load lifetime playtime: " + exception.getMessage());
            return Optional.empty();
        }
    }

    public Optional<Instant> getFirstJoin(UUID uuid) {
        return readInstantByUuid("SELECT first_join FROM lifetime_agg WHERE player_uuid = ?", uuid, "first join");
    }

    public Optional<Instant> getLastJoin(UUID uuid) {
        return readInstantByUuid("SELECT last_join FROM lifetime_agg WHERE player_uuid = ?", uuid, "last join");
    }

    public Optional<Instant> getLastSeen(UUID uuid) {
        String sql = "SELECT last_seen, last_join FROM lifetime_agg WHERE player_uuid = ?";
        try (Connection connection = provider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                Timestamp lastSeen = resultSet.getTimestamp("last_seen");
                if (lastSeen != null) {
                    return Optional.of(lastSeen.toInstant());
                }
                Timestamp lastJoin = resultSet.getTimestamp("last_join");
                return Optional.ofNullable(lastJoin).map(Timestamp::toInstant);
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to load last seen: " + exception.getMessage());
            return Optional.empty();
        }
    }

    public void recordLastSeenAsync(org.bukkit.plugin.Plugin schedulerOwner, UUID uuid, Instant seenAt) {
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(schedulerOwner, () -> {
            try {
                recordLastSeen(uuid, seenAt);
            } catch (SQLException exception) {
                plugin.getLogger().warning("Failed to persist last seen for " + uuid + ": " + exception.getMessage());
            }
        });
    }

    public void recordLastSeen(UUID uuid, Instant seenAt) throws SQLException {
        withSqliteRetry(() -> {
            try (Connection connection = provider.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE lifetime_agg SET last_seen = ? WHERE player_uuid = ?")) {
                statement.setTimestamp(1, Timestamp.from(seenAt));
                statement.setString(2, uuid.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void purgeOldJoins(int retentionDays) throws SQLException {
        if (retentionDays <= 0) {
            return;
        }

        Instant cutoff = Instant.now().minusSeconds(retentionDays * 86_400L);
        withSqliteRetry(() -> {
            try (Connection connection = provider.getConnection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM joins_log WHERE joined_at < ?")) {
                statement.setTimestamp(1, Timestamp.from(cutoff));
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void purgeOldHourlyAggregates(int retentionDays) throws SQLException {
        if (retentionDays <= 0) {
            return;
        }

        Instant cutoff = Instant.now().minusSeconds(retentionDays * 86_400L).truncatedTo(ChronoUnit.HOURS);
        withSqliteRetry(() -> {
            try (Connection connection = provider.getConnection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM hourly_agg WHERE hour_start < ?")) {
                statement.setTimestamp(1, Timestamp.from(cutoff));
                statement.executeUpdate();
            }
            return null;
        });
    }

    public RangeTotals getRangeTotals(UUID uuid, Instant now, String rangeId) {
        String range = normalizeRange(rangeId);
        if (range.equals("ALL")) {
            return getLifetime(uuid)
                    .map(snapshot -> new RangeTotals(snapshot.activeMinutes, snapshot.afkMinutes, snapshot.totalMinutes))
                    .orElseGet(() -> new RangeTotals(0, 0, 0));
        }

        DateRange dateRange = dateRangeFor(range, now);
        String sql = """
                SELECT COALESCE(SUM(active_minutes), 0) AS active,
                       COALESCE(SUM(afk_minutes), 0) AS afk,
                       COALESCE(SUM(total_minutes), 0) AS total
                FROM daily_agg
                WHERE player_uuid = ? AND day >= ? AND day <= ?
                """;

        try (Connection connection = provider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, dateRange.start().toString());
            statement.setString(3, dateRange.end().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return new RangeTotals(0, 0, 0);
                }
                return new RangeTotals(
                        resultSet.getLong("active"),
                        resultSet.getLong("afk"),
                        resultSet.getLong("total")
                );
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to load range totals (" + range + ") for " + uuid + ": " + exception.getMessage());
            return new RangeTotals(0, 0, 0);
        }
    }

    public RangeTotals getRollingTotals(UUID uuid, Instant now, int hours) {
        int safeHours = Math.max(1, Math.min(hours, 24 * 90));
        Instant cutoff = now.minus(safeHours, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        String sql = """
                SELECT COALESCE(SUM(active_minutes), 0) AS active,
                       COALESCE(SUM(afk_minutes), 0) AS afk,
                       COALESCE(SUM(total_minutes), 0) AS total
                FROM hourly_agg
                WHERE player_uuid = ? AND hour_start >= ?
                """;

        try (Connection connection = provider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setTimestamp(2, Timestamp.from(cutoff));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return new RangeTotals(0, 0, 0);
                }
                return new RangeTotals(resultSet.getLong("active"), resultSet.getLong("afk"), resultSet.getLong("total"));
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to load rolling totals (" + safeHours + "h) for " + uuid + ": " + exception.getMessage());
            return new RangeTotals(0, 0, 0);
        }
    }

    public List<LeaderboardEntry> getLeaderboard(String metricId, String rangeId, Instant now, int limit, int offset) {
        List<LeaderboardEntry> leaderboard = new ArrayList<>();
        String metric = normalizeMetric(metricId);
        String range = normalizeRange(rangeId);

        String sql;
        if (range.equals("ALL")) {
            String orderColumn = switch (metric) {
                case "ACTIVE" -> "active_minutes";
                case "AFK" -> "afk_minutes";
                default -> "total_minutes";
            };
            sql = "SELECT player_uuid, active_minutes AS active, afk_minutes AS afk, total_minutes AS total "
                    + "FROM lifetime_agg ORDER BY " + orderColumn + " DESC LIMIT ? OFFSET ?";

            try (Connection connection = provider.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, limit);
                statement.setInt(2, offset);
                appendLeaderboardRows(leaderboard, statement, offset);
            } catch (SQLException exception) {
                plugin.getLogger().warning("Failed to load leaderboard (" + metric + ", " + range + "): " + exception.getMessage());
            }
            return leaderboard;
        }

        DateRange dateRange = dateRangeFor(range, now);
        String orderColumn = switch (metric) {
            case "ACTIVE" -> "active";
            case "AFK" -> "afk";
            default -> "total";
        };
        sql = """
                SELECT player_uuid,
                       SUM(active_minutes) AS active,
                       SUM(afk_minutes) AS afk,
                       SUM(total_minutes) AS total
                FROM daily_agg
                WHERE day >= ? AND day <= ?
                GROUP BY player_uuid
                ORDER BY %s DESC
                LIMIT ? OFFSET ?
                """.formatted(orderColumn);

        try (Connection connection = provider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, dateRange.start().toString());
            statement.setString(2, dateRange.end().toString());
            statement.setInt(3, limit);
            statement.setInt(4, offset);
            appendLeaderboardRows(leaderboard, statement, offset);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to load leaderboard (" + metric + ", " + range + "): " + exception.getMessage());
        }

        return leaderboard;
    }

    public List<PublicLeaderboardEntry> getPublicLeaderboard(String metricId, String rangeId, Instant now, int limit) {
        List<PublicLeaderboardEntry> leaderboard = new ArrayList<>();
        String metric = normalizeMetric(metricId);
        String range = normalizeRange(rangeId);
        int safeLimit = Math.max(1, Math.min(limit, 500));

        String valueExpression = switch (metric) {
            case "ACTIVE" -> "active";
            case "AFK" -> "afk";
            default -> "total";
        };

        String sql;
        if (range.equals("ALL")) {
            sql = """
                    SELECT l.player_uuid,
                           l.active_minutes AS active,
                           l.afk_minutes AS afk,
                           l.total_minutes AS total,
                           l.first_join AS first_seen,
                           COALESCE(p.last_seen, l.last_seen, l.last_join) AS last_seen,
                           p.updated_at AS updated_at,
                           p.username AS username,
                           p.display_name AS display_name
                    FROM lifetime_agg l
                    LEFT JOIN player_profiles p ON p.player_uuid = l.player_uuid
                    ORDER BY %s DESC
                    LIMIT ?
                    """.formatted(valueExpression);
        } else {
            DateRange dateRange = dateRangeFor(range, now);
            sql = """
                    SELECT d.player_uuid,
                           d.active AS active,
                           d.afk AS afk,
                           d.total AS total,
                           l.first_join AS first_seen,
                           COALESCE(p.last_seen, l.last_seen, l.last_join) AS last_seen,
                           p.updated_at AS updated_at,
                           p.username AS username,
                           p.display_name AS display_name
                    FROM (
                        SELECT player_uuid,
                               SUM(active_minutes) AS active,
                               SUM(afk_minutes) AS afk,
                               SUM(total_minutes) AS total
                        FROM daily_agg
                        WHERE day >= ? AND day <= ?
                        GROUP BY player_uuid
                    ) d
                    LEFT JOIN lifetime_agg l ON l.player_uuid = d.player_uuid
                    LEFT JOIN player_profiles p ON p.player_uuid = d.player_uuid
                    ORDER BY %s DESC
                    LIMIT ?
                    """.formatted(valueExpression);

            try (Connection connection = provider.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, dateRange.start().toString());
                statement.setString(2, dateRange.end().toString());
                statement.setInt(3, safeLimit);
                appendPublicLeaderboardRows(leaderboard, statement, metric);
            } catch (SQLException exception) {
                plugin.getLogger().warning("Failed to load public leaderboard (" + metric + ", " + range + "): " + exception.getMessage());
            }
            return leaderboard;
        }

        try (Connection connection = provider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, safeLimit);
            appendPublicLeaderboardRows(leaderboard, statement, metric);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to load public leaderboard (" + metric + ", " + range + "): " + exception.getMessage());
        }
        return leaderboard;
    }

    public RangeTotals getServerRangeTotals(String rangeId, Instant now) {
        String range = normalizeRange(rangeId);
        if (range.equals("ALL")) {
            String sql = """
                    SELECT COALESCE(SUM(active_minutes), 0) AS active,
                           COALESCE(SUM(afk_minutes), 0) AS afk,
                           COALESCE(SUM(total_minutes), 0) AS total
                    FROM lifetime_agg
                    """;
            return singleTotalsQuery(sql);
        }

        DateRange dateRange = dateRangeFor(range, now);
        String sql = """
                SELECT COALESCE(SUM(active_minutes), 0) AS active,
                       COALESCE(SUM(afk_minutes), 0) AS afk,
                       COALESCE(SUM(total_minutes), 0) AS total
                FROM daily_agg
                WHERE day >= ? AND day <= ?
                """;
        try (Connection connection = provider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, dateRange.start().toString());
            statement.setString(2, dateRange.end().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return new RangeTotals(0, 0, 0);
                }
                return new RangeTotals(resultSet.getLong("active"), resultSet.getLong("afk"), resultSet.getLong("total"));
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to load server totals (" + range + "): " + exception.getMessage());
            return new RangeTotals(0, 0, 0);
        }
    }

    public RangeTotals getServerRollingTotals(Instant now, int hours) {
        int safeHours = Math.max(1, Math.min(hours, 24 * 90));
        Instant cutoff = now.minus(safeHours, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        String sql = """
                SELECT COALESCE(SUM(active_minutes), 0) AS active,
                       COALESCE(SUM(afk_minutes), 0) AS afk,
                       COALESCE(SUM(total_minutes), 0) AS total
                FROM hourly_agg
                WHERE hour_start >= ?
                """;

        try (Connection connection = provider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(cutoff));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return new RangeTotals(0, 0, 0);
                }
                return new RangeTotals(resultSet.getLong("active"), resultSet.getLong("afk"), resultSet.getLong("total"));
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to load server rolling totals (" + safeHours + "h): " + exception.getMessage());
            return new RangeTotals(0, 0, 0);
        }
    }

    public int getRollingUniquePlayers(Instant now, int hours) {
        int safeHours = Math.max(1, Math.min(hours, 24 * 90));
        Instant cutoff = now.minus(safeHours, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        String sql = "SELECT COUNT(DISTINCT player_uuid) AS c FROM hourly_agg WHERE hour_start >= ?";
        try (Connection connection = provider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(cutoff));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt("c") : 0;
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to load rolling unique players (" + safeHours + "h): " + exception.getMessage());
            return 0;
        }
    }

    public int getServerUniquePlayers(String rangeId, Instant now) {
        String range = normalizeRange(rangeId);
        String sql;
        if (range.equals("ALL")) {
            sql = "SELECT COUNT(*) AS c FROM lifetime_agg";
            try (Connection connection = provider.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt("c") : 0;
            } catch (SQLException exception) {
                plugin.getLogger().warning("Failed to load server unique players (ALL): " + exception.getMessage());
                return 0;
            }
        }

        DateRange dateRange = dateRangeFor(range, now);
        sql = "SELECT COUNT(DISTINCT player_uuid) AS c FROM daily_agg WHERE day >= ? AND day <= ?";
        try (Connection connection = provider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, dateRange.start().toString());
            statement.setString(2, dateRange.end().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt("c") : 0;
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to load server unique players (" + range + "): " + exception.getMessage());
            return 0;
        }
    }

    public int getServerJoins(String rangeId, Instant now) {
        String range = normalizeRange(rangeId);
        if (range.equals("ALL")) {
            String sql = "SELECT COUNT(*) AS c FROM joins_log";
            try (Connection connection = provider.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt("c") : 0;
            } catch (SQLException exception) {
                plugin.getLogger().warning("Failed to load joins (ALL): " + exception.getMessage());
                return 0;
            }
        }

        ZonedDateTime nowZoned = now.atZone(joinZoneId);
        LocalDate end = nowZoned.toLocalDate();
        LocalDate start = switch (range) {
            case "TODAY" -> end;
            case "7D" -> end.minusDays(6);
            case "30D" -> end.minusDays(29);
            default -> end.minusDays(29);
        };

        Instant from = start.atStartOfDay(joinZoneId).toInstant();
        Instant to = end.plusDays(1).atStartOfDay(joinZoneId).toInstant();

        String sql = "SELECT COUNT(*) AS c FROM joins_log WHERE joined_at >= ? AND joined_at < ?";
        try (Connection connection = provider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(from));
            statement.setTimestamp(2, Timestamp.from(to));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt("c") : 0;
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to load joins (" + range + "): " + exception.getMessage());
            return 0;
        }
    }

    public List<RecentJoinActivity> getRecentJoinActivity(int limit) {
        List<RecentJoinActivity> joins = new ArrayList<>();
        int safeLimit = Math.max(1, Math.min(limit, 50));
        String sql = """
                SELECT j.player_uuid,
                       j.joined_at,
                       p.username,
                       l.first_join
                FROM joins_log j
                LEFT JOIN player_profiles p ON p.player_uuid = j.player_uuid
                LEFT JOIN lifetime_agg l ON l.player_uuid = j.player_uuid
                ORDER BY j.joined_at DESC
                LIMIT ?
                """;

        try (Connection connection = provider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, safeLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    try {
                        UUID uuid = UUID.fromString(resultSet.getString("player_uuid"));
                        Instant joinedAt = instantOrNull(resultSet.getTimestamp("joined_at"));
                        Instant firstJoin = instantOrNull(resultSet.getTimestamp("first_join"));
                        joins.add(new RecentJoinActivity(
                                uuid,
                                fallbackUsername(resultSet.getString("username"), uuid),
                                joinedAt,
                                firstJoin != null && joinedAt != null && Math.abs(firstJoin.toEpochMilli() - joinedAt.toEpochMilli()) < 1000L
                        ));
                    } catch (IllegalArgumentException ignored) {
                        // Ignore malformed UUID rows rather than failing the whole table.
                    }
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to load recent join activity: " + exception.getMessage());
        }
        return joins;
    }

    public AdminServerStats getAdminServerStats(String rangeId, Instant now) {
        String range = normalizeRange(rangeId);
        AdminServerStats stats = new AdminServerStats();
        RangeTotals totals = getServerRangeTotals(range, now);
        stats.activeMinutes = totals.activeMinutes;
        stats.afkMinutes = totals.afkMinutes;
        stats.totalMinutes = totals.totalMinutes;
        stats.playersWithPlaytime = getServerUniquePlayers(range, now);
        stats.totalJoins = getServerJoins(range, now);

        try (Connection connection = provider.getConnection()) {
            Map<String, Long> earliestJoinByPlayer = new HashMap<>();
            Map<String, Integer> totalJoinsByPlayer = new HashMap<>();
            Map<String, Integer> joinsInRangeByPlayer = new HashMap<>();
            Map<LocalDate, Set<String>> dailyUniquePlayers = new HashMap<>();

            JoinWindow joinWindow = joinWindowFor(range, now);
            String sql = "SELECT player_uuid, joined_at FROM joins_log";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String uuid = resultSet.getString("player_uuid");
                    Timestamp timestamp = resultSet.getTimestamp("joined_at");
                    if (uuid == null || timestamp == null) {
                        continue;
                    }

                    Instant joinedAt = timestamp.toInstant();
                    earliestJoinByPlayer.merge(uuid, joinedAt.toEpochMilli(), Math::min);
                    totalJoinsByPlayer.merge(uuid, 1, Integer::sum);

                    if (joinWindow.includes(joinedAt)) {
                        joinsInRangeByPlayer.merge(uuid, 1, Integer::sum);
                        LocalDate day = joinedAt.atZone(joinZoneId).toLocalDate();
                        dailyUniquePlayers.computeIfAbsent(day, ignored -> new HashSet<>()).add(uuid);
                    }
                }
            }

            stats.uniquePlayersJoined = joinsInRangeByPlayer.size();
            if (stats.playersWithPlaytime == 0 && range.equals("ALL")) {
                stats.playersWithPlaytime = earliestJoinByPlayer.size();
            }

            if (range.equals("ALL")) {
                stats.newPlayers = earliestJoinByPlayer.size();
                for (Map.Entry<String, Integer> entry : totalJoinsByPlayer.entrySet()) {
                    if (entry.getValue() > 1) {
                        stats.returningPlayers++;
                        stats.retainedNewPlayers++;
                    }
                }
            } else {
                for (String uuid : joinsInRangeByPlayer.keySet()) {
                    long earliest = earliestJoinByPlayer.getOrDefault(uuid, Long.MAX_VALUE);
                    boolean newInRange = earliest >= joinWindow.from().toEpochMilli() && earliest < joinWindow.to().toEpochMilli();
                    if (newInRange) {
                        stats.newPlayers++;
                        if (totalJoinsByPlayer.getOrDefault(uuid, 0) > 1) {
                            stats.retainedNewPlayers++;
                        }
                    } else {
                        stats.returningPlayers++;
                    }
                }
            }

            if (!dailyUniquePlayers.isEmpty()) {
                long max = 0L;
                long sum = 0L;
                for (Set<String> uniquePlayers : dailyUniquePlayers.values()) {
                    long count = uniquePlayers.size();
                    max = Math.max(max, count);
                    sum += count;
                }
                stats.avgUniquePlayersPerDay = Math.round((double) sum / dailyUniquePlayers.size());
                stats.maxUniquePlayersPerDay = max;
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to load admin server stats (" + range + "): " + exception.getMessage());
        }

        return stats;
    }

    private RangeTotals singleTotalsQuery(String sql) {
        try (Connection connection = provider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return new RangeTotals(0, 0, 0);
            }
            return new RangeTotals(resultSet.getLong("active"), resultSet.getLong("afk"), resultSet.getLong("total"));
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to load totals: " + exception.getMessage());
            return new RangeTotals(0, 0, 0);
        }
    }

    private void appendLeaderboardRows(List<LeaderboardEntry> rows, PreparedStatement statement, int offset) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            int rank = offset + 1;
            while (resultSet.next()) {
                try {
                    rows.add(new LeaderboardEntry(
                            UUID.fromString(resultSet.getString("player_uuid")),
                            resultSet.getLong("active"),
                            resultSet.getLong("afk"),
                            resultSet.getLong("total"),
                            rank++
                    ));
                } catch (IllegalArgumentException ignored) {
                    // Ignore malformed UUID rows rather than failing the whole page.
                }
            }
        }
    }

    private void appendPublicLeaderboardRows(List<PublicLeaderboardEntry> rows, PreparedStatement statement, String metric) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            int rank = 1;
            while (resultSet.next()) {
                try {
                    UUID uuid = UUID.fromString(resultSet.getString("player_uuid"));
                    long active = resultSet.getLong("active");
                    long afk = resultSet.getLong("afk");
                    long total = resultSet.getLong("total");
                    long value = switch (metric) {
                        case "ACTIVE" -> active;
                        case "AFK" -> afk;
                        default -> total;
                    };
                    rows.add(new PublicLeaderboardEntry(
                            rank++,
                            uuid,
                            fallbackUsername(resultSet.getString("username"), uuid),
                            resultSet.getString("display_name"),
                            active,
                            afk,
                            total,
                            value,
                            instantOrNull(resultSet.getTimestamp("first_seen")),
                            instantOrNull(resultSet.getTimestamp("last_seen")),
                            instantOrNull(resultSet.getTimestamp("updated_at"))
                    ));
                } catch (IllegalArgumentException ignored) {
                    // Ignore malformed UUID rows rather than failing the whole export.
                }
            }
        }
    }

    private Optional<Instant> readInstantByUuid(String sql, UUID uuid, String label) {
        try (Connection connection = provider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                Timestamp timestamp = resultSet.getTimestamp(1);
                return Optional.ofNullable(timestamp).map(Timestamp::toInstant);
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to load " + label + ": " + exception.getMessage());
            return Optional.empty();
        }
    }

    private String fallbackUsername(String username, UUID uuid) {
        if (username != null && !username.isBlank()) {
            return username;
        }
        return uuid.toString().substring(0, 8);
    }

    private Instant instantOrNull(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void tryAddLastSeenColumn(Statement statement) {
        boolean columnExists = false;
        try {
            statement.execute(dialect.lifetimeAggAddLastSeenColumn());
            columnExists = true;
        } catch (SQLException exception) {
            String message = exception.getMessage();
            if (message == null) {
                return;
            }
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("duplicate column")
                    || normalized.contains("already exists")
                    || normalized.contains("duplicate")) {
                columnExists = true;
            } else {
                plugin.getLogger().warning("Failed while ensuring lifetime_agg.last_seen exists: " + exception.getMessage());
            }
        }

        if (!columnExists) {
            return;
        }

        try {
            statement.executeUpdate(dialect.lifetimeAggBackfillLastSeenColumn());
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed while backfilling lifetime_agg.last_seen: " + exception.getMessage());
        }
    }

    private DateRange dateRangeFor(String range, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LocalDate start = switch (range) {
            case "TODAY" -> today;
            case "7D" -> today.minusDays(6);
            case "30D" -> today.minusDays(29);
            default -> today.minusDays(29);
        };
        return new DateRange(start, today);
    }

    private JoinWindow joinWindowFor(String range, Instant now) {
        if (range.equals("ALL")) {
            return new JoinWindow(Instant.EPOCH, Instant.ofEpochMilli(Long.MAX_VALUE));
        }

        ZonedDateTime nowZoned = now.atZone(joinZoneId);
        LocalDate end = nowZoned.toLocalDate();
        LocalDate start = switch (range) {
            case "TODAY" -> end;
            case "7D" -> end.minusDays(6);
            case "30D" -> end.minusDays(29);
            default -> end.minusDays(29);
        };

        return new JoinWindow(start.atStartOfDay(joinZoneId).toInstant(), end.plusDays(1).atStartOfDay(joinZoneId).toInstant());
    }

    private String normalizeRange(String rangeId) {
        if (rangeId == null) {
            return "ALL";
        }
        return switch (rangeId.toUpperCase(Locale.ROOT)) {
            case "TODAY", "7D", "30D", "ALL" -> rangeId.toUpperCase(Locale.ROOT);
            default -> "ALL";
        };
    }

    private String normalizeMetric(String metricId) {
        if (metricId == null) {
            return "TOTAL";
        }
        return switch (metricId.toUpperCase(Locale.ROOT)) {
            case "ACTIVE", "AFK", "TOTAL" -> metricId.toUpperCase(Locale.ROOT);
            default -> "TOTAL";
        };
    }

    private <T> T withSqliteRetry(SqlCallable<T> action) throws SQLException {
        try {
            return action.call();
        } catch (SQLException exception) {
            if (provider.reopenIfSqliteDbMoved(exception)) {
                return action.call();
            }
            throw exception;
        }
    }

    @FunctionalInterface
    private interface SqlCallable<T> {
        T call() throws SQLException;
    }

    public record JoinRecord(UUID uuid, Instant joinedAt) {
    }

    private record DateRange(LocalDate start, LocalDate end) {
    }

    private record JoinWindow(Instant from, Instant to) {
        private boolean includes(Instant instant) {
            return !instant.isBefore(from) && instant.isBefore(to);
        }
    }
}
