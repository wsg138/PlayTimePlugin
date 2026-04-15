package org.enthusia.playtime.command;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.model.LeaderboardEntry;
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.gui.LeaderboardGui;
import org.enthusia.playtime.gui.PlaytimeMainGui;
import org.enthusia.playtime.gui.admin.AdminMainGui;
import org.enthusia.playtime.gui.admin.AdminPlayersGui;
import org.enthusia.playtime.gui.admin.AdminServerActivityGui;
import org.enthusia.playtime.service.PlaytimeRuntime;
import org.enthusia.playtime.util.RomanTiering;
import org.enthusia.playtime.util.TimeFormats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class PlaytimeCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.GOLD + "[Playtime] " + ChatColor.YELLOW;

    private final PlayTimePlugin plugin;

    public PlaytimeCommand(PlayTimePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        PlaytimeRuntime runtime = plugin.runtime();
        if (runtime == null) {
            send(sender, ChatColor.RED + "Playtime runtime is not available.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("roman")) {
            showNumerals(sender, runtime);
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("admin")) {
            return handleAdmin(sender, label, args);
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("top")) {
            handleTop(sender, runtime, args);
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("numerals")) {
            showNumerals(sender, runtime);
            return true;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                send(sender, ChatColor.RED + "Usage: /" + label + " <player|top|admin|numerals>");
                return true;
            }
            if (!sender.hasPermission("playtime.base")) {
                send(sender, ChatColor.RED + "You don't have permission.");
                return true;
            }
            if (runtime.config().isGuiEnabled()) {
                new PlaytimeMainGui(plugin, player).open();
            } else {
                showPlaytime(sender, runtime, player.getUniqueId(), player.getName());
            }
            return true;
        }

        String targetName = args[0];
        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            if (sender instanceof Player player && player.getUniqueId().equals(online.getUniqueId())) {
                showPlaytime(sender, runtime, online.getUniqueId(), online.getName());
                return true;
            }
            if (!sender.hasPermission("playtime.others")) {
                send(sender, ChatColor.RED + "You don't have permission to view others' playtime.");
                return true;
            }
            showPlaytime(sender, runtime, online.getUniqueId(), online.getName());
            return true;
        }

        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(targetName);
        UUID cachedUuid = runtime.headCache().findUuidByName(targetName);
        if ((offline == null || offline.getUniqueId() == null) && cachedUuid != null) {
            offline = Bukkit.getOfflinePlayer(cachedUuid);
        }

        if (offline == null || offline.getUniqueId() == null) {
            send(sender, ChatColor.RED + "Player '" + targetName + "' has never joined.");
            return true;
        }

        if (sender instanceof Player player && player.getUniqueId().equals(offline.getUniqueId())) {
            showPlaytime(sender, runtime, offline.getUniqueId(), offline.getName() != null ? offline.getName() : targetName);
            return true;
        }
        if (!sender.hasPermission("playtime.others")) {
            send(sender, ChatColor.RED + "You don't have permission to view others' playtime.");
            return true;
        }

        showPlaytime(sender, runtime, offline.getUniqueId(), offline.getName() != null ? offline.getName() : targetName);
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("playtime.admin.base")) {
            send(sender, ChatColor.RED + "You don't have permission to use admin playtime tools.");
            return true;
        }

        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                send(sender, "Usage: /" + label + " admin <players|activity|reload|debug>");
                return true;
            }
            new AdminMainGui(plugin, player).open();
            return true;
        }

        String subcommand = args[1].toLowerCase(Locale.ROOT);
        if (subcommand.equals("players")) {
            if (!(sender instanceof Player player)) {
                send(sender, "This admin GUI is in-game only.");
                return true;
            }
            new AdminPlayersGui(plugin, player).open();
            return true;
        }

        if (subcommand.equals("activity")) {
            if (!(sender instanceof Player player)) {
                send(sender, "This admin GUI is in-game only.");
                return true;
            }
            new AdminServerActivityGui(plugin, player).open();
            return true;
        }

        if (subcommand.equals("reload")) {
            if (!sender.hasPermission("playtime.admin.reload")) {
                send(sender, ChatColor.RED + "You don't have permission to reload.");
                return true;
            }
            if (plugin.reloadPluginRuntime()) {
                send(sender, ChatColor.GREEN + "Playtime plugin reloaded safely.");
            } else {
                send(sender, ChatColor.RED + "Reload failed. Check console for details.");
            }
            return true;
        }

        if (subcommand.equals("debug")) {
            if (!sender.hasPermission("playtime.admin.debug")) {
                send(sender, ChatColor.RED + "You don't have permission to debug.");
                return true;
            }
            PlaytimeRuntime runtime = plugin.runtime();
            if (runtime == null) {
                send(sender, ChatColor.RED + "Playtime runtime is not available.");
                return true;
            }
            send(sender, ChatColor.YELLOW + "Storage: " + ChatColor.AQUA + runtime.config().getStorageType().name().toLowerCase(Locale.ROOT));
            send(sender, ChatColor.YELLOW + "Flush interval: " + ChatColor.AQUA + runtime.config().getFlushIntervalTicks() + " ticks");
            send(sender, ChatColor.YELLOW + "Suspicious threshold: " + ChatColor.AQUA
                    + runtime.config().sampling().suspicion().maxCountedConsecutiveMinutes() + " counted minutes");
            return true;
        }

        send(sender, "Usage: /" + label + " admin [players|activity|reload|debug]");
        return true;
    }

    private void handleTop(CommandSender sender, PlaytimeRuntime runtime, String[] args) {
        if (!sender.hasPermission("playtime.base")) {
            send(sender, ChatColor.RED + "You don't have permission to view playtime leaderboards.");
            return;
        }

        String metric = runtime.config().leaderboards().defaultMetric().toLowerCase(Locale.ROOT);
        String range = runtime.config().leaderboards().defaultRange().toLowerCase(Locale.ROOT);
        int page = 1;

        if (args.length >= 2) {
            metric = args[1].toLowerCase(Locale.ROOT);
            if (!metric.equals("active") && !metric.equals("afk") && !metric.equals("total")) {
                send(sender, ChatColor.RED + "Unknown metric '" + metric + "'. Use active/afk/total.");
                return;
            }
        }

        if (args.length >= 3) {
            range = args[2].toLowerCase(Locale.ROOT);
            if (!range.equals("today") && !range.equals("7d") && !range.equals("30d") && !range.equals("all")) {
                send(sender, ChatColor.RED + "Unknown range '" + range + "'. Use today/7d/30d/all.");
                return;
            }
        }

        if (args.length >= 4) {
            try {
                page = Math.max(1, Integer.parseInt(args[3]));
            } catch (NumberFormatException exception) {
                send(sender, ChatColor.RED + "Page must be a number.");
                return;
            }
        }

        if (sender instanceof Player player) {
            new LeaderboardGui(plugin, player, metric.toUpperCase(Locale.ROOT), range.toUpperCase(Locale.ROOT), page).open();
            return;
        }

        int pageSize = 10;
        int offset = (page - 1) * pageSize;
        List<LeaderboardEntry> rows = runtime.readService().getLeaderboard(metric.toUpperCase(Locale.ROOT), range.toUpperCase(Locale.ROOT), pageSize, offset);
        if (rows.isEmpty()) {
            send(sender, ChatColor.RED + "No leaderboard data for that metric/range yet.");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "[Playtime] " + ChatColor.YELLOW
                + niceMetric(metric) + " leaderboard (" + niceRange(range) + "), page " + page + ":");
        for (LeaderboardEntry entry : rows) {
            String name = resolveName(entry.uuid);
            sender.sendMessage(ChatColor.GRAY + "#" + entry.rank + " "
                    + ChatColor.AQUA + name + ChatColor.GRAY + " - "
                    + ChatColor.YELLOW + TimeFormats.formatMinutes(entry.totalMinutes)
                    + ChatColor.GRAY + " (A: " + ChatColor.GREEN + TimeFormats.formatMinutes(entry.activeMinutes)
                    + ChatColor.GRAY + ", AFK: " + ChatColor.RED + TimeFormats.formatMinutes(entry.afkMinutes) + ChatColor.GRAY + ")");
        }
    }

    private void showPlaytime(CommandSender sender, PlaytimeRuntime runtime, UUID uuid, String name) {
        Optional<PlaytimeSnapshot> optional = runtime.readService().getLifetime(uuid);
        if (optional.isEmpty()) {
            send(sender, ChatColor.RED + "No playtime recorded for " + name + ".");
            return;
        }

        PlaytimeSnapshot snapshot = optional.get();
        sender.sendMessage(ChatColor.GOLD + "[Playtime] " + ChatColor.YELLOW + "Playtime for " + name + ":");
        sender.sendMessage(ChatColor.GRAY + "Total: " + ChatColor.AQUA + TimeFormats.formatMinutes(snapshot.totalMinutes)
                + ChatColor.GRAY + " (Active: " + ChatColor.GREEN + TimeFormats.formatMinutes(snapshot.activeMinutes)
                + ChatColor.GRAY + ", AFK: " + ChatColor.RED + TimeFormats.formatMinutes(snapshot.afkMinutes) + ChatColor.GRAY + ")");
    }

    private void showNumerals(CommandSender sender, PlaytimeRuntime runtime) {
        if (!sender.hasPermission("playtime.base")) {
            send(sender, ChatColor.RED + "You don't have permission.");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "[Playtime] " + ChatColor.YELLOW + "Playtime numeral tiers:");
        if (sender instanceof Player player) {
            Optional<PlaytimeSnapshot> optional = runtime.readService().getLifetime(player.getUniqueId());
            if (optional.isPresent()) {
                PlaytimeSnapshot snapshot = optional.get();
                RomanTiering.Tier tier = RomanTiering.getTierForMinutes(snapshot.activeMinutes);
                sender.sendMessage(ChatColor.GRAY + "You (active): " + ChatColor.AQUA + TimeFormats.formatMinutes(snapshot.activeMinutes)
                        + ChatColor.GRAY + " -> " + ChatColor.GOLD + (tier == null ? ChatColor.DARK_GRAY + "None" : tier.label()));
            } else {
                sender.sendMessage(ChatColor.GRAY + "You: " + ChatColor.RED + "No playtime recorded yet.");
            }
        }

        StringBuilder builder = new StringBuilder();
        for (RomanTiering.Tier tier : RomanTiering.getTiers()) {
            if (builder.length() > 0) {
                builder.append(ChatColor.DARK_GRAY).append(" | ");
            }
            builder.append(ChatColor.GRAY).append(tier.label()).append(ChatColor.DARK_GRAY).append(":")
                    .append(ChatColor.AQUA).append(tier.requiredHours()).append("h");
        }
        sender.sendMessage(builder.toString());
    }

    private String resolveName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        PlaytimeRuntime runtime = plugin.runtime();
        if (runtime != null) {
            String cached = runtime.headCache().getLastKnownName(uuid);
            if (cached != null && !cached.isBlank()) {
                return cached;
            }
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        if (offline.getName() != null) {
            return offline.getName();
        }
        return uuid.toString().substring(0, 8);
    }

    private String niceMetric(String metric) {
        return switch (metric.toLowerCase(Locale.ROOT)) {
            case "active" -> "Active";
            case "afk" -> "AFK";
            default -> "Total";
        };
    }

    private String niceRange(String range) {
        return switch (range.toLowerCase(Locale.ROOT)) {
            case "today" -> "Today";
            case "7d" -> "Last 7 days";
            case "30d" -> "Last 30 days";
            default -> "All time";
        };
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            if ("admin".startsWith(prefix) && sender.hasPermission("playtime.admin.base")) {
                result.add("admin");
            }
            if ("top".startsWith(prefix) && sender.hasPermission("playtime.base")) {
                result.add("top");
            }
            if ("numerals".startsWith(prefix) && sender.hasPermission("playtime.base")) {
                result.add("numerals");
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.add(player.getName());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("top")) {
            addMatches(result, args[1], "active", "afk", "total");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("top")) {
            addMatches(result, args[2], "today", "7d", "30d", "all");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            addMatches(result, args[1], "players", "activity", "reload", "debug");
        }
        return result;
    }

    private void addMatches(List<String> result, String prefix, String... values) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value.startsWith(lowerPrefix)) {
                result.add(value);
            }
        }
    }
}
