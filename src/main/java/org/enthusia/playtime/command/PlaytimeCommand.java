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
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.model.LeaderboardEntry;
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.gui.LeaderboardGui;
import org.enthusia.playtime.gui.PlaytimeMainGui;
import org.enthusia.playtime.gui.admin.AdminMainGui;
import org.enthusia.playtime.gui.admin.AdminPlayersGui;
import org.enthusia.playtime.gui.admin.AdminServerActivityGui;
import org.enthusia.playtime.util.RomanTiering;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class PlaytimeCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.GOLD + "[Playtime] " + ChatColor.YELLOW;

    private final PlayTimePlugin plugin;
    private final PlaytimeRepository repository;

    public PlaytimeCommand(PlayTimePlugin plugin) {
        this.plugin = plugin;
        this.repository = plugin.getRepository();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (command.getName().equalsIgnoreCase("roman")) {
            showNumerals(sender);
            return true;
        }

        // /playtime admin ...
        if (args.length >= 1 && args[0].equalsIgnoreCase("admin")) {
            return handleAdmin(sender, label, args);
        }

        // /playtime top [metric] [range] [page]
        if (args.length >= 1 && args[0].equalsIgnoreCase("top")) {
            handleTop(sender, args);
            return true;
        }

        // /playtime numerals  (list roman-style tiers)
        if (args.length >= 1 && args[0].equalsIgnoreCase("numerals")) {
            showNumerals(sender);
            return true;
        }

        // /playtime          (self -> GUI or text fallback)
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                send(sender, ChatColor.RED + "Usage: /" + label + " <player|top|admin|numerals>");
                return true;
            }
            if (!sender.hasPermission("playtime.base")) {
                send(sender, ChatColor.RED + "You don't have permission.");
                return true;
            }
            if (plugin.getConfig().getBoolean("gui.enabled", true)) {
                new PlaytimeMainGui(plugin, player).open();
            } else {
                showPlaytime(sender, player.getUniqueId(), player.getName());
            }
            return true;
        }

        // /playtime <player> (chat fallback)
        if (!sender.hasPermission("playtime.others") && !sender.hasPermission("playtime.base")) {
            send(sender, ChatColor.RED + "You don't have permission to view others' playtime.");
            return true;
        }

        String targetName = args[0];

        // Prefer online player first
        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            showPlaytime(sender, online.getUniqueId(), online.getName());
            return true;
        }

        // Fallback to offline cache (avoid Mojang lookups for random names)
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(targetName);
        if (offline == null || offline.getUniqueId() == null) {
            send(sender, ChatColor.RED + "Player '" + targetName + "' has never joined.");
            return true;
        }

        showPlaytime(sender, offline.getUniqueId(), offline.getName());
        return true;
    }

    // ---- /playtime admin ----

    private boolean handleAdmin(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("playtime.admin.base")) {
            send(sender, ChatColor.RED + "You don't have permission to use admin playtime tools.");
            return true;
        }

        // /playtime admin  -> open main admin GUI (if player)
        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                send(sender, "Usage: /" + label + " admin <players|activity|reload|debug>");
                return true;
            }
            new AdminMainGui(plugin, player).open();
            return true;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);

        if (sub.equals("players")) {
            if (!(sender instanceof Player player)) {
                send(sender, "This admin GUI is in-game only.");
                return true;
            }
            new AdminPlayersGui(plugin, player).open();
            return true;
        }

        if (sub.equals("activity")) {
            if (!(sender instanceof Player player)) {
                send(sender, "This admin GUI is in-game only.");
                return true;
            }
            new AdminServerActivityGui(plugin, player).open();
            return true;
        }

        if (sub.equals("reload")) {
            if (!sender.hasPermission("playtime.admin.reload")) {
                send(sender, ChatColor.RED + "You don't have permission to reload.");
                return true;
            }
            plugin.reloadConfig();
            send(sender, ChatColor.GREEN + "Config reloaded.");
            return true;
        }

        if (sub.equals("debug")) {
            if (!sender.hasPermission("playtime.admin.debug")) {
                send(sender, ChatColor.RED + "You don't have permission to debug.");
                return true;
            }
            send(sender, ChatColor.YELLOW + "Debug info is not implemented yet.");
            return true;
        }

        send(sender, "Usage: /" + label + " admin [players|activity|reload|debug]");
        return true;
    }

    // ---- /playtime top ----

    private void handleTop(CommandSender sender, String[] args) {
        if (!sender.hasPermission("playtime.base")) {
            send(sender, ChatColor.RED + "You don't have permission to view playtime leaderboards.");
            return;
        }

        // Defaults
        String metric = "total"; // active|afk|total
        String range = "all";    // today|7d|30d|all
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
                page = Integer.parseInt(args[3]);
                if (page < 1) page = 1;
            } catch (NumberFormatException e) {
                send(sender, ChatColor.RED + "Page must be a number.");
                return;
            }
        }

        if (sender instanceof Player player) {
            // GUI for players
            new LeaderboardGui(plugin, player,
                    metric.toUpperCase(Locale.ROOT),
                    range.toUpperCase(Locale.ROOT),
                    page).open();
        } else {
            // Console fallback: simple text leaderboard
            int pageSize = 10;
            int offset = (page - 1) * pageSize;

            List<LeaderboardEntry> rows = repository.getLeaderboard(
                    metric.toUpperCase(Locale.ROOT),
                    range.toUpperCase(Locale.ROOT),
                    Instant.now(),
                    pageSize,
                    offset
            );

            if (rows.isEmpty()) {
                send(sender, ChatColor.RED + "No leaderboard data for that metric/range yet.");
                return;
            }

            String metricNice = switch (metric) {
                case "active" -> "Active";
                case "afk" -> "AFK";
                default -> "Total";
            };

            String rangeNice = switch (range) {
                case "today" -> "Today";
                case "7d" -> "Last 7 days";
                case "30d" -> "Last 30 days";
                default -> "All time";
            };

            sender.sendMessage(ChatColor.GOLD + "[Playtime] " + ChatColor.YELLOW +
                    metricNice + " leaderboard (" + rangeNice + "), page " + page + ":");

            for (LeaderboardEntry entry : rows) {
                String name = resolveName(entry.uuid);
                String line = ChatColor.GRAY + "#" + entry.rank + " " +
                        ChatColor.AQUA + name + ChatColor.GRAY + " - " +
                        ChatColor.YELLOW + formatMinutes(entry.totalMinutes) +
                        ChatColor.GRAY + " (A: " + ChatColor.GREEN + formatMinutes(entry.activeMinutes) +
                        ChatColor.GRAY + ", AFK: " + ChatColor.RED + formatMinutes(entry.afkMinutes) +
                        ChatColor.GRAY + ")";
                sender.sendMessage(line);
            }

            sender.sendMessage(ChatColor.DARK_GRAY +
                    "Use /playtime top " + metric + " " + range + " " + (page + 1) + " for next page.");
        }
    }

    private String resolveName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }

        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        if (offline.getName() != null) {
            return offline.getName();
        }

        return uuid.toString().substring(0, 8);
    }

    // ---- /playtime (self/other text helper) ----

    private void showPlaytime(CommandSender sender, UUID uuid, String name) {
        Optional<PlaytimeSnapshot> opt = repository.getLifetime(uuid);
        if (opt.isEmpty()) {
            send(sender, ChatColor.RED + "No playtime recorded for " + name + ".");
            return;
        }

        PlaytimeSnapshot snap = opt.get();

        String header = ChatColor.GOLD + "[Playtime] " + ChatColor.YELLOW + "Playtime for " + name + ":";
        String totals = ChatColor.GRAY + "Total: "
                + ChatColor.AQUA + formatMinutes(snap.totalMinutes)
                + ChatColor.GRAY + " (Active: "
                + ChatColor.GREEN + formatMinutes(snap.activeMinutes)
                + ChatColor.GRAY + ", AFK: "
                + ChatColor.RED + formatMinutes(snap.afkMinutes)
                + ChatColor.GRAY + ")";

        sender.sendMessage(header);
        sender.sendMessage(totals);
    }

    private void showNumerals(CommandSender sender) {
        if (!sender.hasPermission("playtime.base")) {
            send(sender, ChatColor.RED + "You don't have permission.");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "[Playtime] " + ChatColor.YELLOW + "Playtime numeral tiers:");

        if (sender instanceof Player player) {
            Optional<PlaytimeSnapshot> opt = repository.getLifetime(player.getUniqueId());
            if (opt.isPresent()) {
                PlaytimeSnapshot snap = opt.get();
                long hours = snap.activeMinutes / 60;
                RomanTiering.Tier tier = RomanTiering.getTierForMinutes(snap.activeMinutes);
                String tierLabel = tier != null ? tier.label() : ChatColor.DARK_GRAY + "None";
                sender.sendMessage(ChatColor.GRAY + "You (active): " + ChatColor.AQUA + formatMinutes(snap.activeMinutes) +
                        ChatColor.GRAY + " (" + ChatColor.YELLOW + hours + "h" + ChatColor.GRAY + ") -> " +
                        ChatColor.GOLD + tierLabel);
            } else {
                sender.sendMessage(ChatColor.GRAY + "You: " + ChatColor.RED + "No playtime recorded yet.");
            }
        }

        StringBuilder line = new StringBuilder();
        for (RomanTiering.Tier tier : RomanTiering.getTiers()) {
            if (line.length() > 0) {
                line.append(ChatColor.DARK_GRAY).append(" | ");
            }
            line.append(ChatColor.GRAY).append(tier.label())
                    .append(ChatColor.DARK_GRAY).append(":")
                    .append(ChatColor.AQUA).append(tier.requiredHours()).append("h");
        }

        sender.sendMessage(line.toString());
    }

    private static void send(CommandSender sender, String msg) {
        sender.sendMessage(PREFIX + msg);
    }

    private static String formatMinutes(long minutes) {
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

    // ---- Tab complete ----

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

            for (Player p : Bukkit.getOnlinePlayers()) {
                String name = p.getName();
                if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.add(name);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("top")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            if ("active".startsWith(prefix)) result.add("active");
            if ("afk".startsWith(prefix)) result.add("afk");
            if ("total".startsWith(prefix)) result.add("total");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("top")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            if ("today".startsWith(prefix)) result.add("today");
            if ("7d".startsWith(prefix)) result.add("7d");
            if ("30d".startsWith(prefix)) result.add("30d");
            if ("all".startsWith(prefix)) result.add("all");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (String s : new String[]{"players", "activity", "reload", "debug"}) {
                if (s.startsWith(prefix)) {
                    result.add(s);
                }
            }
        }

        return result;
    }
}
