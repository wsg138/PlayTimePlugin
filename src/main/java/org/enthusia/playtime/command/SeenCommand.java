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
import org.enthusia.playtime.util.TimeFormats;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class SeenCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.GOLD + "[Playtime] " + ChatColor.YELLOW;

    private final PlayTimePlugin plugin;

    public SeenCommand(PlayTimePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("playtime.seen")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "You don't have permission to use this command.");
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
            showSeen(sender, runtime, player.getUniqueId(), player.getName(), true);
            return true;
        }

        String targetName = args[0];
        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            showSeen(sender, runtime, online.getUniqueId(), online.getName(), true);
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

        showSeen(sender, runtime, offline.getUniqueId(), offline.getName() != null ? offline.getName() : targetName, false);
        return true;
    }

    private void showSeen(CommandSender sender, PlaytimeRuntime runtime, UUID uuid, String name, boolean online) {
        if (online) {
            sender.sendMessage(PREFIX + ChatColor.AQUA + name + ChatColor.YELLOW + " is currently online.");
            return;
        }

        Optional<Instant> lastSeenOpt = runtime.repository().getLastSeen(uuid);
        if (lastSeenOpt.isEmpty()) {
            sender.sendMessage(PREFIX + ChatColor.RED + "No last-seen record found for " + name + ".");
            return;
        }

        Instant lastSeen = lastSeenOpt.get();
        Instant now = Instant.now();
        long agoMillis = Math.max(0L, Duration.between(lastSeen, now).toMillis());
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM d, uuuu h:mm a z", Locale.US).withZone(runtime.config().joins().zoneId());

        sender.sendMessage(PREFIX + name + ChatColor.GRAY + " | "
                + ChatColor.YELLOW + "Last seen " + ChatColor.AQUA + TimeFormats.formatDurationMillis(agoMillis)
                + ChatColor.YELLOW + " ago "
                + ChatColor.GRAY + "(" + ChatColor.WHITE + formatter.format(lastSeen) + ChatColor.GRAY + ")");
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
