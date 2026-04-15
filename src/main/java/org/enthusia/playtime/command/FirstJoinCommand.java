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
import org.enthusia.playtime.service.PlaytimeRuntime;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class FirstJoinCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.GOLD + "[Playtime] " + ChatColor.YELLOW;

    private final PlayTimePlugin plugin;

    public FirstJoinCommand(PlayTimePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("playtime.firstjoined")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "You don't have permission to view first join info.");
            return true;
        }

        PlaytimeRuntime runtime = plugin.runtime();
        if (runtime == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Playtime runtime is not available.");
            return true;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(PREFIX + ChatColor.RED + "Usage: /" + label + " <player>");
                return true;
            }
            showFirstJoin(sender, runtime, player.getUniqueId(), player.getName());
            return true;
        }

        String targetName = args[0];
        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            showFirstJoin(sender, runtime, online.getUniqueId(), online.getName());
            return true;
        }

        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(targetName);
        UUID cachedUuid = runtime.headCache().findUuidByName(targetName);
        if ((offline == null || offline.getUniqueId() == null) && cachedUuid != null) {
            offline = Bukkit.getOfflinePlayer(cachedUuid);
        }
        if (offline == null || offline.getUniqueId() == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Player '" + targetName + "' has never joined.");
            return true;
        }

        showFirstJoin(sender, runtime, offline.getUniqueId(), offline.getName() != null ? offline.getName() : targetName);
        return true;
    }

    private void showFirstJoin(CommandSender sender, PlaytimeRuntime runtime, UUID uuid, String name) {
        Optional<Instant> firstJoinOpt = runtime.repository().getFirstJoin(uuid);
        if (firstJoinOpt.isEmpty()) {
            sender.sendMessage(PREFIX + ChatColor.RED + "No first-join record found for " + name + ".");
            return;
        }

        ZoneId zoneId = runtime.config().joins().zoneId();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM d, uuuu h:mm a z", Locale.US).withZone(zoneId);
        ZonedDateTime then = firstJoinOpt.get().atZone(zoneId);
        ZonedDateTime now = Instant.now().atZone(zoneId);

        sender.sendMessage(PREFIX + "First join for " + ChatColor.AQUA + name + ChatColor.YELLOW
                + ": " + ChatColor.WHITE + formatter.format(then)
                + ChatColor.GRAY + " (" + formatAgo(then, now) + " ago)");
    }

    private String formatAgo(ZonedDateTime then, ZonedDateTime now) {
        if (now.isBefore(then)) {
            return "just now";
        }

        Duration duration = Duration.between(then, now);
        long seconds = duration.getSeconds();
        long days = seconds / 86_400L;
        seconds %= 86_400L;
        long hours = seconds / 3_600L;
        seconds %= 3_600L;
        long minutes = seconds / 60L;

        StringBuilder builder = new StringBuilder();
        if (days > 0) {
            builder.append(days).append(" day").append(days == 1 ? "" : "s");
        }
        if (hours > 0) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(hours).append(" hour").append(hours == 1 ? "" : "s");
        }
        if (days == 0 && hours == 0) {
            builder.append(minutes).append(" minute").append(minutes == 1 ? "" : "s");
        }
        return builder.isEmpty() ? "just now" : builder.toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.add(player.getName());
                }
            }
        }
        return result;
    }
}
