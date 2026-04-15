package org.enthusia.playtime.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.activity.ActivityState;
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.data.model.RangeTotals;
import org.enthusia.playtime.service.PlaytimeRuntime;
import org.enthusia.playtime.util.RomanTiering;
import org.enthusia.playtime.util.TimeFormats;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class PlaytimePlaceholderExpansion extends PlaceholderExpansion {

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
        if (player == null) {
            return "";
        }

        PlaytimeRuntime runtime = plugin.runtime();
        if (runtime == null) {
            return "";
        }

        UUID uuid = player.getUniqueId();
        String id = identifier.toLowerCase(Locale.ROOT);

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

    private boolean isMetric(String metric) {
        return metric.equals("total") || metric.equals("active") || metric.equals("afk");
    }

    private boolean isRange(String range) {
        return range.equals("today") || range.equals("7d") || range.equals("30d") || range.equals("all");
    }
}
