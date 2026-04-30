package org.enthusia.playtime.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.activity.ActivityState;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.data.model.PublicLeaderboardEntry;
import org.enthusia.playtime.data.model.RangeTotals;
import org.enthusia.playtime.service.PlaytimeRuntime;
import org.enthusia.playtime.util.RomanTiering;
import org.enthusia.playtime.util.TimeFormats;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class PlaytimePlaceholderExpansion extends PlaceholderExpansion {

    private static final int MAX_TOP_RANK = 100;

    private final PlayTimePlugin plugin;

    public PlaytimePlaceholderExpansion(PlayTimePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "playtime";
    }

    @Override
    public String getAuthor() {
        return plugin.getDescription().getAuthors().isEmpty()
                ? "Enthusia"
                : String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String identifier) {
        PlaytimeRuntime runtime = plugin.runtime();
        if (runtime == null) {
            return "";
        }

        String id = identifier.toLowerCase(Locale.ROOT);
        if (id.startsWith("top_")) {
            return resolveTopPlaceholder(runtime, id.substring(4));
        }

        if (player == null) {
            return "";
        }

        UUID uuid = player.getUniqueId();

        if (id.equals("state")) {
            ActivityState state = runtime.activityTracker().getState(uuid, System.currentTimeMillis());
            return state.name();
        }

        if (id.equals("session") || id.equals("session_formatted")) {
            long millis = runtime.sessionManager().getSessionLengthMillis(uuid);
            return id.endsWith("_formatted") ? TimeFormats.formatDurationMillis(millis) : String.valueOf(millis / 1000L);
        }

        if (id.equals("total") || id.equals("total_formatted")
                || id.equals("active") || id.equals("active_formatted")
                || id.equals("afk") || id.equals("afk_formatted")
                || id.equals("roman")) {

            Optional<PlaytimeSnapshot> optional = runtime.readService().getLifetime(uuid);
            PlaytimeSnapshot snapshot = optional.orElseGet(() -> new PlaytimeSnapshot(0, 0, 0));

            if (id.equals("roman")) {
                RomanTiering.Tier tier = RomanTiering.getTierForMinutes(snapshot.activeMinutes);
                return tier == null ? "" : tier.label();
            }

            boolean formatted = id.endsWith("_formatted");
            if (id.startsWith("total")) {
                return formatted ? TimeFormats.formatMinutes(snapshot.totalMinutes) : String.valueOf(snapshot.totalMinutes * 60L);
            }
            if (id.startsWith("active")) {
                return formatted ? TimeFormats.formatMinutes(snapshot.activeMinutes) : String.valueOf(snapshot.activeMinutes * 60L);
            }
            return formatted ? TimeFormats.formatMinutes(snapshot.afkMinutes) : String.valueOf(snapshot.afkMinutes * 60L);
        }

        String[] parts = id.split("_");
        if (parts.length >= 2 && parts.length <= 3) {
            String metric = parts[0];
            String range = parts[1];
            boolean formatted = parts.length == 3 && parts[2].equals("formatted");
            if (!isMetric(metric) || !isRange(range)) {
                return null;
            }

            RangeTotals totals = runtime.readService().getRangeTotals(uuid, range.toUpperCase(Locale.ROOT));
            long minutes = switch (metric) {
                case "active" -> totals.activeMinutes;
                case "afk" -> totals.afkMinutes;
                default -> totals.totalMinutes;
            };
            return formatted ? TimeFormats.formatMinutes(minutes) : String.valueOf(minutes * 60L);
        }

        return null;
    }

    private String resolveTopPlaceholder(PlaytimeRuntime runtime, String body) {
        String[] parts = body.split("_");
        if (parts.length != 4) {
            return fallback();
        }

        String metric = parts[0];
        String range = parts[1];
        int rank;
        try {
            rank = Integer.parseInt(parts[2]);
        } catch (NumberFormatException exception) {
            return fallback();
        }
        String field = parts[3];

        PlaytimeConfig.Placeholders config = plugin.getRuntimeConfig().placeholders();
        if (rank < 1 || rank > Math.min(MAX_TOP_RANK, config.topLeaderboardMaxRank())) {
            return fallback();
        }
        if (!isMetric(metric) || !isRange(range) || !isField(field)) {
            return fallback();
        }

        List<PublicLeaderboardEntry> entries = runtime.readService().getPublicLeaderboard(metric, range, config.topLeaderboardMaxRank());
        PublicLeaderboardEntry entry = entries.stream()
                .filter(candidate -> candidate.rank == rank)
                .findFirst()
                .orElse(null);
        if (entry == null) {
            return fallback();
        }

        return switch (field) {
            case "name" -> safe(entry.username);
            case "uuid" -> entry.uuid == null ? fallback() : entry.uuid.toString();
            case "value" -> String.valueOf(metricValueMinutes(entry, metric));
            case "formatted" -> TimeFormats.formatMinutes(metricValueMinutes(entry, metric));
            default -> fallback();
        };
    }

    private long metricValueMinutes(PublicLeaderboardEntry entry, String metric) {
        return switch (metric) {
            case "active" -> entry.activeMinutes;
            case "afk" -> entry.afkMinutes;
            default -> entry.totalMinutes;
        };
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? fallback() : value;
    }

    private String fallback() {
        String fallback = plugin.getRuntimeConfig().placeholders().leaderboardFallback();
        return fallback == null ? "" : fallback;
    }

    private boolean isMetric(String metric) {
        return metric.equals("total") || metric.equals("active") || metric.equals("afk");
    }

    private boolean isRange(String range) {
        return range.equals("today") || range.equals("7d") || range.equals("30d") || range.equals("all");
    }

    private boolean isField(String field) {
        return field.equals("name") || field.equals("uuid") || field.equals("value") || field.equals("formatted");
    }
}
