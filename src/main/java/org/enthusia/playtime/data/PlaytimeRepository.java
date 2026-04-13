package org.enthusia.playtime.data;

import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.enthusia.playtime.data.model.LeaderboardEntry;
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.data.model.RangeTotals;

import java.sql.*;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class PlaytimeRepository {

    private final JavaPlugin plugin;
    private final DatabaseProvider provider;
    private final SqlDialect dialect;
    private final ZoneId joinZoneId;

    public PlaytimeRepository(JavaPlugin plugin, DatabaseProvider provider, PlaytimeConfig config) {
        this.plugin = plugin;
        this.provider = provider;
        this.dialect = provider.getDialect();
        this.joinZoneId = ZoneId.of(config.getJoinTimezoneId());
    }

    public void initSchema() throws SQLException {
        withSqliteRetry(() -> {
            try (Connection conn = provider.getConnection();
                 Statement st = conn.createStatement()) {

                st.execute(dialect.dailyAggCreateTable());
                st.execute(dialect.lifetimeAggCreateTable());
                st.execute(dialect.joinsLogCreateTable());
            }
            return null;
        });
    }

    public void recordJoin(UUID uuid, Instant joinedAt) throws SQLException {
        withSqliteRetry(() -> {
            try (Connection conn = provider.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO joins_log (player_uuid, joined_at) VALUES (?, ?)")) {
                    ps.setString(1, uuid.toString());
                    ps.setTimestamp(2, Timestamp.from(joinedAt));
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement(dialect.lifetimeAggUpsert())) {
                    ps.setString(1, uuid.toString());
                    ps.setTimestamp(2, Timestamp.from(joinedAt));
                    ps.setTimestamp(3, Timestamp.from(joinedAt));
                    ps.setInt(4, 0);
                    ps.setInt(5, 0);
                    ps.setInt(6, 0);
                    ps.executeUpdate();
                }

                conn.commit();
            }
            return null;
        });
    }

    public void recordMinute(UUID uuid, Instant now, int activeMinutes, int afkMinutes) throws SQLException {
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        int total = activeMinutes + afkMinutes;

        withSqliteRetry(() -> {
            try (Connection conn = provider.getConnection()) {
                conn.setAutoCommit(false);

                try (PreparedStatement ps = conn.prepareStatement(dialect.dailyAggUpsert())) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, day.toString());
                    ps.setInt(3, activeMinutes);
                    ps.setInt(4, afkMinutes);
                    ps.setInt(5, total);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement(dialect.lifetimeAggUpsert())) {
                    ps.setString(1, uuid.toString());
                    ps.setTimestamp(2, Timestamp.from(now));
                    ps.setTimestamp(3, Timestamp.from(now));
                    ps.setInt(4, activeMinutes);
                    ps.setInt(5, afkMinutes);
                    ps.setInt(6, total);
                    ps.executeUpdate();
                }

                conn.commit();
            }
            return null;
        });
    }

    public Optional<PlaytimeSnapshot> getLifetime(UUID uuid) {
        try (Connection conn = provider.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT active_minutes, afk_minutes, total_minutes FROM lifetime_agg WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                long active = rs.getLong("active_minutes");
                long afk = rs.getLong("afk_minutes");
                long total = rs.getLong("total_minutes");
                return Optional.of(new PlaytimeSnapshot(active, afk, total));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to load lifetime playtime: " + e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<Instant> getFirstJoin(UUID uuid) {
        try (Connection conn = provider.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT first_join FROM lifetime_agg WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                Timestamp ts = rs.getTimestamp("first_join");
                return Optional.ofNullable(ts).map(Timestamp::toInstant);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to load first join: " + e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<Instant> getLastJoin(UUID uuid) {
        try (Connection conn = provider.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT last_join FROM lifetime_agg WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                Timestamp ts = rs.getTimestamp("last_join");
                return Optional.ofNullable(ts).map(Timestamp::toInstant);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to load last join: " + e.getMessage());
            return Optional.empty();
        }
    }

    public void purgeOldJoins(int retentionDays) throws SQLException {
        // <= 0 means "never purge" -> keep true all-time stats
        if (retentionDays <= 0) return;

        long cutoffMillis = System.currentTimeMillis() - (retentionDays * 86_400_000L); // 24h * 60m * 60s * 1000

        withSqliteRetry(() -> {
            try (Connection conn = provider.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM joins_log WHERE joined_at < ?"
                 )) {
                ps.setTimestamp(1, new Timestamp(cutoffMillis));
                ps.executeUpdate();
            }
            return null;
        });
    }


    // ------- Per-player range totals -------

    public RangeTotals getRangeTotals(UUID uuid, Instant now, String rangeId) {
        String range = (rangeId == null) ? "ALL" : rangeId.toUpperCase(Locale.ROOT);

        if (range.equals("ALL")) {
            Optional<PlaytimeSnapshot> snapOpt = getLifetime(uuid);
            if (snapOpt.isEmpty()) {
                return new RangeTotals(0, 0, 0);
            }
            PlaytimeSnapshot s = snapOpt.get();
            return new RangeTotals(s.activeMinutes, s.afkMinutes, s.totalMinutes);
        }

        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LocalDate start;
        switch (range) {
            case "TODAY" -> start = today;
            case "7D" -> start = today.minusDays(6);
            case "30D" -> start = today.minusDays(29);
            default -> start = today.minusDays(29);
        }

        String startStr = start.toString();
        String endStr = today.toString();

        String sql = "SELECT " +
                "COALESCE(SUM(active_minutes), 0) AS active, " +
                "COALESCE(SUM(afk_minutes), 0) AS afk, " +
                "COALESCE(SUM(total_minutes), 0) AS total " +
                "FROM daily_agg " +
                "WHERE player_uuid = ? AND day >= ? AND day <= ?";

        try (Connection conn = provider.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, startStr);
            ps.setString(3, endStr);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new RangeTotals(0, 0, 0);
                }
                long active = rs.getLong("active");
                long afk = rs.getLong("afk");
                long total = rs.getLong("total");
                return new RangeTotals(active, afk, total);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to load range totals (" + range + ") for " + uuid + ": " + e.getMessage());
            return new RangeTotals(0, 0, 0);
        }
    }

    // ------- Leaderboard queries -------

    public List<LeaderboardEntry> getLeaderboard(String metricId,
                                                 String rangeId,
                                                 Instant now,
                                                 int limit,
                                                 int offset) {
        List<LeaderboardEntry> list = new ArrayList<>();

        String metric = metricId == null ? "TOTAL" : metricId.toUpperCase(Locale.ROOT);
        String range = rangeId == null ? "ALL" : rangeId.toUpperCase(Locale.ROOT);

        String orderColumn;
        switch (metric) {
            case "ACTIVE" -> orderColumn = "active";
            case "AFK" -> orderColumn = "afk";
            default -> {
                metric = "TOTAL";
                orderColumn = "total";
            }
        }

        boolean allTime = range.equals("ALL");

        String sql;
        if (allTime) {
            String orderColLifetime = switch (metric) {
                case "ACTIVE" -> "active_minutes";
                case "AFK" -> "afk_minutes";
                default -> "total_minutes";
            };

            sql = "SELECT player_uuid, active_minutes AS active, afk_minutes AS afk, total_minutes AS total " +
                    "FROM lifetime_agg " +
                    "ORDER BY " + orderColLifetime + " DESC " +
                    "LIMIT ? OFFSET ?";

            try (Connection conn = provider.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, limit);
                ps.setInt(2, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    int rank = offset + 1;
                    while (rs.next()) {
                        String uuidStr = rs.getString("player_uuid");
                        long active = rs.getLong("active");
                        long afk = rs.getLong("afk");
                        long total = rs.getLong("total");
                        try {
                            UUID uuid = UUID.fromString(uuidStr);
                            list.add(new LeaderboardEntry(uuid, active, afk, total, rank++));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to load leaderboard (" + metric + ", " + range + "): " + e.getMessage());
            }

            return list;
        }

        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LocalDate start;
        switch (range) {
            case "TODAY" -> start = today;
            case "7D" -> start = today.minusDays(6);
            case "30D" -> start = today.minusDays(29);
            default -> start = today.minusDays(29);
        }

        String startStr = start.toString();
        String endStr = today.toString();

        sql = "SELECT player_uuid, " +
                "SUM(active_minutes) AS active, " +
                "SUM(afk_minutes) AS afk, " +
                "SUM(total_minutes) AS total " +
                "FROM daily_agg " +
                "WHERE day >= ? AND day <= ? " +
                "GROUP BY player_uuid " +
                "ORDER BY " + orderColumn + " DESC " +
                "LIMIT ? OFFSET ?";

        try (Connection conn = provider.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, startStr);
            ps.setString(2, endStr);
            ps.setInt(3, limit);
            ps.setInt(4, offset);
            try (ResultSet rs = ps.executeQuery()) {
                int rank = offset + 1;
                while (rs.next()) {
                    String uuidStr = rs.getString("player_uuid");
                    long active = rs.getLong("active");
                    long afk = rs.getLong("afk");
                    long total = rs.getLong("total");
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        list.add(new LeaderboardEntry(uuid, active, afk, total, rank++));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to load leaderboard (" + metric + ", " + range + "): " + e.getMessage());
        }

        return list;
    }

    // ------- Server-wide stats for admin GUI -------

    /**
     * Server totals over a range (ALL / TODAY / 7D / 30D).
     */
    public RangeTotals getServerRangeTotals(String rangeId, Instant now) {
        String range = (rangeId == null) ? "ALL" : rangeId.toUpperCase(Locale.ROOT);

        if (range.equals("ALL")) {
            String sql = "SELECT " +
                    "COALESCE(SUM(active_minutes), 0) AS active, " +
                    "COALESCE(SUM(afk_minutes), 0) AS afk, " +
                    "COALESCE(SUM(total_minutes), 0) AS total " +
                    "FROM lifetime_agg";
            try (Connection conn = provider.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new RangeTotals(0, 0, 0);
                long active = rs.getLong("active");
                long afk = rs.getLong("afk");
                long total = rs.getLong("total");
                return new RangeTotals(active, afk, total);
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to load server totals (ALL): " + e.getMessage());
                return new RangeTotals(0, 0, 0);
            }
        }

        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LocalDate start;
        switch (range) {
            case "TODAY" -> start = today;
            case "7D" -> start = today.minusDays(6);
            case "30D" -> start = today.minusDays(29);
            default -> start = today.minusDays(29);
        }

        String startStr = start.toString();
        String endStr = today.toString();

        String sql = "SELECT " +
                "COALESCE(SUM(active_minutes), 0) AS active, " +
                "COALESCE(SUM(afk_minutes), 0) AS afk, " +
                "COALESCE(SUM(total_minutes), 0) AS total " +
                "FROM daily_agg " +
                "WHERE day >= ? AND day <= ?";

        try (Connection conn = provider.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, startStr);
            ps.setString(2, endStr);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new RangeTotals(0, 0, 0);
                long active = rs.getLong("active");
                long afk = rs.getLong("afk");
                long total = rs.getLong("total");
                return new RangeTotals(active, afk, total);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to load server totals (" + range + "): " + e.getMessage());
            return new RangeTotals(0, 0, 0);
        }
    }

    /**
     * Approx. number of unique players with recorded playtime in range.
     */
    public int getServerUniquePlayers(String rangeId, Instant now) {
        String range = (rangeId == null) ? "ALL" : rangeId.toUpperCase(Locale.ROOT);

        if (range.equals("ALL")) {
            String sql = "SELECT COUNT(*) AS c FROM lifetime_agg";
            try (Connection conn = provider.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return 0;
                return rs.getInt("c");
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to load server unique players (ALL): " + e.getMessage());
                return 0;
            }
        }

        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LocalDate start;
        switch (range) {
            case "TODAY" -> start = today;
            case "7D" -> start = today.minusDays(6);
            case "30D" -> start = today.minusDays(29);
            default -> start = today.minusDays(29);
        }

        String startStr = start.toString();
        String endStr = today.toString();

        String sql = "SELECT COUNT(DISTINCT player_uuid) AS c FROM daily_agg WHERE day >= ? AND day <= ?";

        try (Connection conn = provider.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, startStr);
            ps.setString(2, endStr);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return 0;
                return rs.getInt("c");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to load server unique players (" + range + "): " + e.getMessage());
            return 0;
        }
    }

    /**
     * Counts joins (join events recorded in joins_log) in a range.
     */
    public int getServerJoins(String rangeId, Instant now) {
        String range = (rangeId == null) ? "ALL" : rangeId.toUpperCase(Locale.ROOT);

        if (range.equals("ALL")) {
            String sql = "SELECT COUNT(*) AS c FROM joins_log";
            try (Connection conn = provider.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return 0;
                return rs.getInt("c");
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to load joins (ALL): " + e.getMessage());
                return 0;
            }
        }

        ZonedDateTime nowZ = now.atZone(joinZoneId);
        LocalDate today = nowZ.toLocalDate();
        LocalDate start;
        switch (range) {
            case "TODAY" -> start = today;
            case "7D" -> start = today.minusDays(6);
            case "30D" -> start = today.minusDays(29);
            default -> start = today.minusDays(29);
        }

        LocalDate end = today;
        Instant from = start.atStartOfDay(joinZoneId).toInstant();
        Instant to = end.plusDays(1).atStartOfDay(joinZoneId).toInstant();

        String sql = "SELECT COUNT(*) AS c FROM joins_log WHERE joined_at >= ? AND joined_at < ?";

        try (Connection conn = provider.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(from));
            ps.setTimestamp(2, Timestamp.from(to));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return 0;
                return rs.getInt("c");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to load joins (" + range + "): " + e.getMessage());
            return 0;
        }
    }

    private <T> T withSqliteRetry(SqlCallable<T> action) throws SQLException {
        try {
            return action.call();
        } catch (SQLException ex) {
            if (provider.reopenIfSqliteDbMoved(ex)) {
                return action.call();
            }
            throw ex;
        }
    }

    @FunctionalInterface
    private interface SqlCallable<T> {
        T call() throws SQLException;
    }
}
