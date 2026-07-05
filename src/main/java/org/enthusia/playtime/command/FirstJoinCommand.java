package org.enthusia.playtime.command;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
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
    private static final int TARGET_ARG_COUNT = 1;
    private static final long MIN_DURATION_PART = 1L;
    private static final long SINGULAR_AMOUNT = 1L;
    private static final long SECONDS_PER_DAY = 86_400L;
    private static final long SECONDS_PER_HOUR = 3_600L;
    private static final long SECONDS_PER_MINUTE = 60L;

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

        showTargetFirstJoin(sender, runtime, args[0]);
        return true;
    }

    private void showTargetFirstJoin(CommandSender sender, PlaytimeRuntime runtime, String targetName) {
        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            showFirstJoin(sender, runtime, online.getUniqueId(), online.getName());
            return;
        }

        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(targetName);
        UUID cachedUuid = runtime.headCache().findUuidByName(targetName);
        if (offline == null || offline.getUniqueId() == null) {
            if (cachedUuid != null) {
                showFirstJoin(sender, runtime, cachedUuid, targetName);
                return;
            }
            sender.sendMessage(PREFIX + ChatColor.RED + "Player '" + targetName + "' has never joined.");
            return;
        }

        showFirstJoin(sender, runtime, offline.getUniqueId(), offline.getName() != null ? offline.getName() : targetName);
    }

    private void showFirstJoin(CommandSender sender, PlaytimeRuntime runtime, UUID uuid, String name) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Optional<Instant> firstJoinOpt = runtime.repository().getFirstJoin(uuid);
                if (!plugin.isEnabled()) {
                    return;
                }
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> sendFirstJoin(sender, runtime, name, firstJoinOpt));
                } catch (IllegalPluginAccessException exception) {
                    if (plugin.isEnabled()) {
                        throw exception;
                    }
                }
            });
        } catch (IllegalPluginAccessException exception) {
            if (plugin.isEnabled()) {
                throw exception;
            }
        }
    }

    private void sendFirstJoin(CommandSender sender, PlaytimeRuntime runtime, String name, Optional<Instant> firstJoinOpt) {
        PlaytimeRuntime current = plugin.runtime();
        if (current != runtime) {
            return;
        }
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
        long days = seconds / SECONDS_PER_DAY;
        seconds %= SECONDS_PER_DAY;
        long hours = seconds / SECONDS_PER_HOUR;
        seconds %= SECONDS_PER_HOUR;
        long minutes = seconds / SECONDS_PER_MINUTE;

        StringBuilder builder = new StringBuilder();
        appendDurationPart(builder, days, "day");
        appendDurationPart(builder, hours, "hour");
        if (days == 0 && hours == 0) {
            appendDurationPart(builder, minutes, "minute");
        }
        return builder.isEmpty() ? "just now" : builder.toString();
    }

    private void appendDurationPart(StringBuilder builder, long amount, String unit) {
        if (amount < MIN_DURATION_PART) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(", ");
        }
        builder.append(amount).append(' ').append(unit);
        if (amount != SINGULAR_AMOUNT) {
            builder.append('s');
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == TARGET_ARG_COUNT) {
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
