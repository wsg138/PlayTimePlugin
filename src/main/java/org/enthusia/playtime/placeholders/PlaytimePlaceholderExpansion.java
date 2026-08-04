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
import org.enthusia.playtime.util.NumeralTierCatalog;
import org.enthusia.playtime.util.TierColorFormatter;
import org.enthusia.playtime.util.TimeFormats;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class PlaytimePlaceholderExpansion extends PlaceholderExpansion {

    private static final int MAX_TOP_RANK = 100;
    private static final String METRIC_TOTAL = "total";
    private static final String METRIC_ACTIVE = "active";
    private static final String METRIC_AFK = "afk";
    private static final String FORMATTED_SUFFIX = "_formatted";

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
        runtime.counters().placeholderCachedReturns.increment();

        String id = identifier.toLowerCase(Locale.ROOT);
        if (id.startsWith("top_")) {
            return resolveTopPlaceholder(runtime, id.substring(4));
        }

        if (player == null) {
            return "";
        }

        return resolvePlayerPlaceholder(runtime, player, id);
    }

    private String resolvePlayerPlaceholder(PlaytimeRuntime runtime, Player player, String id) {
        UUID uuid = player.getUniqueId();

        if (id.equals("state")) {
            ActivityState state = runtime.activityTracker().getState(uuid, System.currentTimeMillis());
            return state.name();
        }

        if (isSessionPlaceholder(id)) {
            return resolveSessionPlaceholder(runtime, uuid, id);
        }

        if (isLifetimePlaceholder(id)) {
            return resolveLifetimePlaceholder(runtime, uuid, id);
        }

        return resolveRangePlaceholder(runtime, uuid, id);
    }

    private boolean isSessionPlaceholder(String id) {
        return id.equals("session") || id.equals("session_formatted");
    }

    private String resolveSessionPlaceholder(PlaytimeRuntime runtime, UUID uuid, String id) {
        long millis = runtime.sessionManager().getSessionLengthMillis(uuid);
        return id.endsWith(FORMATTED_SUFFIX) ? TimeFormats.formatDurationMillis(millis) : String.valueOf(millis / 1000L);
    }

    private boolean isLifetimePlaceholder(String id) {
        return id.equals(METRIC_TOTAL) || id.equals(METRIC_TOTAL + FORMATTED_SUFFIX)
                || id.equals(METRIC_ACTIVE) || id.equals(METRIC_ACTIVE + FORMATTED_SUFFIX)
                || id.equals(METRIC_AFK) || id.equals(METRIC_AFK + FORMATTED_SUFFIX)
                || id.equals("roman") || id.equals("roman_colored") || id.equals("roman_mm");
    }

    private String resolveLifetimePlaceholder(PlaytimeRuntime runtime, UUID uuid, String id) {
        Optional<PlaytimeSnapshot> optional = runtime.readService().getLifetime(uuid);
        boolean loading = optional.isEmpty() && runtime.readService().isLifetimeLoading(uuid);
        if (isRomanPlaceholder(id)) {
            return resolveRomanPlaceholder(runtime.config().numerals(), optional, loading, id);
        }
        return resolveLifetimeMetricPlaceholder(optional, loading, id);
    }

    static String resolveLifetimeMetricPlaceholder(
            Optional<PlaytimeSnapshot> optional,
            boolean loading,
            String id
    ) {
        if (loading) {
            return id.equals(METRIC_ACTIVE) ? "0" : "";
        }
        PlaytimeSnapshot snapshot = optional.orElseGet(() -> new PlaytimeSnapshot(0, 0, 0));
        return formatMinutesForPlaceholder(snapshotMetricMinutes(snapshot, id), id.endsWith(FORMATTED_SUFFIX));
    }

    static String resolveRomanPlaceholder(
            PlaytimeConfig.Numerals numerals,
            Optional<PlaytimeSnapshot> optional,
            boolean loading,
            String id
    ) {
        if (loading) {
            return "";
        }
        if (!numerals.enabled()) {
            return "";
        }
        PlaytimeSnapshot snapshot = optional.orElseGet(() -> new PlaytimeSnapshot(0, 0, 0));
        NumeralTierCatalog.Tier tier = numerals.catalog().tierForMinutes(snapshot.activeMinutes).orElse(null);
        if (tier == null) {
            return "";
        }
        return switch (id) {
            case "roman_colored" -> TierColorFormatter.apply(tier.color(), tier.label());
            case "roman_mm" -> TierColorFormatter.applyMiniMessage(tier.color(), tier.label());
            default -> tier.label();
        };
    }

    private boolean isRomanPlaceholder(String id) {
        return id.equals("roman") || id.equals("roman_colored") || id.equals("roman_mm");
    }

    private String resolveRangePlaceholder(PlaytimeRuntime runtime, UUID uuid, String id) {
        String[] parts = id.split("_");
        if (parts.length >= 2 && parts.length <= 3) {
            String metric = parts[0];
            String range = parts[1];
            boolean formatted = parts.length == 3 && parts[2].equals("formatted");
            if (!isMetric(metric) || !isRange(range)) {
                return null;
            }

            RangeTotals totals = runtime.readService().getRangeTotals(uuid, range.toUpperCase(Locale.ROOT));
            if (totals.totalMinutes <= 0L && runtime.readService().isRangeLoading(uuid, range.toUpperCase(Locale.ROOT))) {
                return "";
            }
            return formatMinutesForPlaceholder(rangeMetricMinutes(totals, metric), formatted);
        }

        return null;
    }

    private String resolveTopPlaceholder(PlaytimeRuntime runtime, String body) {
        String[] parts = body.split("_");
        if (parts.length != 4) {
            return fallback();
        }

        TopPlaceholder placeholder = parseTopPlaceholder(parts);
        if (placeholder == null) {
            return fallback();
        }

        PlaytimeConfig.Placeholders config = plugin.getRuntimeConfig().placeholders();
        if (placeholder.rank() < 1 || placeholder.rank() > Math.min(MAX_TOP_RANK, config.topLeaderboardMaxRank())) {
            return fallback();
        }
        if (!isMetric(placeholder.metric()) || !isRange(placeholder.range()) || !isField(placeholder.field())) {
            return fallback();
        }

        List<PublicLeaderboardEntry> entries = runtime.readService().getPublicLeaderboard(placeholder.metric(), placeholder.range(), config.topLeaderboardMaxRank());
        if (entries.isEmpty()) {
            runtime.counters().placeholderStaleRefreshes.increment();
        }
        PublicLeaderboardEntry entry = entries.stream()
                .filter(candidate -> candidate.rank == placeholder.rank())
                .findFirst()
                .orElse(null);
        if (entry == null) {
            return fallback();
        }

        return switch (placeholder.field()) {
            case "name" -> safe(entry.username);
            case "uuid" -> entry.uuid == null ? fallback() : entry.uuid.toString();
            case "value" -> String.valueOf(metricValueMinutes(entry, placeholder.metric()));
            case "formatted" -> TimeFormats.formatMinutes(metricValueMinutes(entry, placeholder.metric()));
            default -> fallback();
        };
    }

    private TopPlaceholder parseTopPlaceholder(String[] parts) {
        try {
            return new TopPlaceholder(parts[0], parts[1], Integer.parseInt(parts[2]), parts[3]);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static long snapshotMetricMinutes(PlaytimeSnapshot snapshot, String id) {
        if (id.startsWith(METRIC_TOTAL)) {
            return snapshot.totalMinutes;
        }
        if (id.startsWith(METRIC_ACTIVE)) {
            return snapshot.activeMinutes;
        }
        return snapshot.afkMinutes;
    }

    private long rangeMetricMinutes(RangeTotals totals, String metric) {
        return switch (metric) {
            case METRIC_ACTIVE -> totals.activeMinutes;
            case METRIC_AFK -> totals.afkMinutes;
            default -> totals.totalMinutes;
        };
    }

    private static String formatMinutesForPlaceholder(long minutes, boolean formatted) {
        return formatted ? TimeFormats.formatMinutes(minutes) : String.valueOf(minutes * 60L);
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
        return metric.equals(METRIC_TOTAL) || metric.equals(METRIC_ACTIVE) || metric.equals(METRIC_AFK);
    }

    private boolean isRange(String range) {
        return range.equals("today") || range.equals("7d") || range.equals("30d") || range.equals("all");
    }

    private boolean isField(String field) {
        return field.equals("name") || field.equals("uuid") || field.equals("value") || field.equals("formatted");
    }

    private record TopPlaceholder(String metric, String range, int rank, String field) {
    }
}
