package org.enthusia.playtime.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.activity.ActivityState;
import org.enthusia.playtime.activity.ActivityTracker;
import org.enthusia.playtime.api.PlaytimeService;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.data.model.RangeTotals;
import org.enthusia.playtime.util.RomanTiering;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class PlaytimePlaceholderExpansion extends PlaceholderExpansion {

    private final PlayTimePlugin plugin;
    @SuppressWarnings("unused")
    private final PlaytimeService playtimeService;
    private final PlaytimeRepository repository;

    public PlaytimePlaceholderExpansion(PlayTimePlugin plugin,
                                        PlaytimeService service) {
        this.plugin = plugin;
        this.playtimeService = service;
        this.repository = plugin.getRepository();
    }

    @Override
    public String getIdentifier() {
        // %playtime_*%
        return "playtime";
    }

    @Override
    public String getAuthor() {
        if (!plugin.getDescription().getAuthors().isEmpty()) {
            return String.join(", ", plugin.getDescription().getAuthors());
        }
        return "Enthusia";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        // Keep registered across /papi reload
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

        UUID uuid = player.getUniqueId();
        String id = identifier.toLowerCase(Locale.ROOT);

        // --- Live state ---
        if (id.equals("state")) {
            ActivityTracker tracker = plugin.getActivityTracker();
            ActivityState state = tracker.getState(uuid, System.currentTimeMillis());
            return state != null ? state.name() : "UNKNOWN";
        }

        // --- Base lifetime totals ---
        if (id.equals("total") || id.equals("total_formatted") ||
                id.equals("active") || id.equals("active_formatted") ||
                id.equals("afk") || id.equals("afk_formatted") ||
                id.equals("roman")) {

            Optional<PlaytimeSnapshot> opt = repository.getLifetime(uuid);
            PlaytimeSnapshot snap = opt.orElseGet(() -> new PlaytimeSnapshot(0, 0, 0));

            boolean formatted = id.endsWith("_formatted");

            if (id.equals("roman")) {
                RomanTiering.Tier tier = RomanTiering.getTierForMinutes(snap.activeMinutes);
                return tier != null ? tier.label() : "";
            }

            if (id.startsWith("total")) {
                return formatted
                        ? formatMinutes(snap.totalMinutes)
                        : String.valueOf(snap.totalMinutes * 60L);
            }
            if (id.startsWith("active")) {
                return formatted
                        ? formatMinutes(snap.activeMinutes)
                        : String.valueOf(snap.activeMinutes * 60L);
            }
            if (id.startsWith("afk")) {
                return formatted
                        ? formatMinutes(snap.afkMinutes)
                        : String.valueOf(snap.afkMinutes * 60L);
            }
        }

        // --- Range-based totals ---
        // pattern: <metric>_<range>[_formatted]
        // metric: total|active|afk
        // range: today|7d|30d|all
        String[] parts = id.split("_");
        if (parts.length >= 2 && parts.length <= 3) {
            String metric = parts[0]; // total / active / afk
            String range = parts[1];  // today / 7d / 30d / all
            boolean formatted = (parts.length == 3 && parts[2].equals("formatted"));

            if (!isMetric(metric) || !isRange(range)) {
                return null;
            }

            String rangeId = range.toUpperCase(Locale.ROOT);
            RangeTotals totals = repository.getRangeTotals(uuid, Instant.now(), rangeId);

            long minutes;
            if (metric.equals("total")) {
                minutes = totals.totalMinutes;
            } else if (metric.equals("active")) {
                minutes = totals.activeMinutes;
            } else {
                minutes = totals.afkMinutes;
            }

            return formatted
                    ? formatMinutes(minutes)
                    : String.valueOf(minutes * 60L);
        }

        return null;
    }

    private boolean isMetric(String metric) {
        return metric.equals("total") || metric.equals("active") || metric.equals("afk");
    }

    private boolean isRange(String range) {
        return range.equals("today") || range.equals("7d")
                || range.equals("30d") || range.equals("all");
    }

    private String formatMinutes(long minutes) {
        if (minutes <= 0) return "0m";
        long hours = minutes / 60;
        long mins = minutes % 60;
        if (hours <= 0) {
            return mins + "m";
        }
        if (mins == 0) {
            return hours + "h";
        }
        return hours + "h " + mins + "m";
    }
}
