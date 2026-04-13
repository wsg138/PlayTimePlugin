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
import org.enthusia.playtime.config.PlaytimeConfig;
import org.enthusia.playtime.data.PlaytimeRepository;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class FirstJoinCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.GOLD + "[Playtime] " + ChatColor.YELLOW;

    private final PlayTimePlugin plugin;
    private final PlaytimeRepository repository;
    private final ZoneId zoneId;
    private final DateTimeFormatter formatter;

    public FirstJoinCommand(PlayTimePlugin plugin,
                            PlaytimeRepository repository,
                            PlaytimeConfig config) {
        this.plugin = plugin;
        this.repository = repository;
        // Config should be EST-equivalent; default is "America/New_York"
        this.zoneId = ZoneId.of(config.getJoinTimezoneId());
        this.formatter = DateTimeFormatter.ofPattern("MMM d, uuuu h:mm a z", Locale.US)
                .withZone(zoneId);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!sender.hasPermission("playtime.firstjoined")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "You don't have permission to view first join info.");
            return true;
        }

        // /firstjoin or /fj (self)
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(PREFIX + ChatColor.RED + "Usage: /" + label + " <player>");
                return true;
            }
            showFirstJoin(sender, player.getUniqueId(), player.getName());
            return true;
        }

        // /firstjoin <player>
        String targetName = args[0];

        // Prefer exact online match
        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            showFirstJoin(sender, online.getUniqueId(), online.getName());
            return true;
        }

        // Fallback to cached offline player
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(targetName);
        if (offline == null || offline.getUniqueId() == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Player '" + targetName + "' has never joined.");
            return true;
        }

        showFirstJoin(sender, offline.getUniqueId(), offline.getName() != null ? offline.getName() : targetName);
        return true;
    }

    private void showFirstJoin(CommandSender sender, UUID uuid, String name) {
        Optional<Instant> firstJoinOpt = repository.getFirstJoin(uuid);
        if (firstJoinOpt.isEmpty()) {
            sender.sendMessage(PREFIX + ChatColor.RED + "No first-join record found for " + name + ".");
            return;
        }

        Instant firstJoin = firstJoinOpt.get();
        ZonedDateTime firstTime = firstJoin.atZone(zoneId);
        ZonedDateTime now = Instant.now().atZone(zoneId);

        String formattedDate = formatter.format(firstTime);
        String ago = formatAgo(firstTime, now);

        String line = ChatColor.GOLD + "[Playtime] " + ChatColor.YELLOW +
                "First join for " + ChatColor.AQUA + name + ChatColor.YELLOW +
                ": " + ChatColor.WHITE + formattedDate +
                ChatColor.GRAY + " (" + ago + " ago)";

        sender.sendMessage(line);
    }

    private String formatAgo(ZonedDateTime then, ZonedDateTime now) {
        if (now.isBefore(then)) {
            return "just now";
        }

        Duration d = Duration.between(then, now);
        long seconds = d.getSeconds();

        long days = seconds / (60 * 60 * 24);
        seconds %= (60 * 60 * 24);
        long hours = seconds / (60 * 60);
        seconds %= (60 * 60);
        long minutes = seconds / 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append(" day").append(days == 1 ? "" : "s");
        }
        if (hours > 0) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(hours).append(" hour").append(hours == 1 ? "" : "s");
        }
        if (days == 0 && hours == 0) {
            // For very recent joins, show minutes
            sb.append(minutes).append(" minute").append(minutes == 1 ? "" : "s");
        }
        if (sb.isEmpty()) {
            sb.append("just now");
        }
        return sb.toString();
    }

    // ---- Tab complete ----

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                String name = p.getName();
                if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.add(name);
                }
            }
        }
        return result;
    }
}
