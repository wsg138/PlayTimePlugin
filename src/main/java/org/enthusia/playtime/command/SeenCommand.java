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
import org.enthusia.playtime.util.TimeFormats;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class SeenCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.GOLD + "[Playtime] " + ChatColor.YELLOW;

    private final PlaytimeRepository repository;
    private final ZoneId zoneId;
    private final DateTimeFormatter formatter;

    public SeenCommand(PlayTimePlugin plugin, PlaytimeRepository repository, PlaytimeConfig config) {
        this.repository = repository;
        this.zoneId = ZoneId.of(config.getJoinTimezoneId());
        this.formatter = DateTimeFormatter.ofPattern("MMM d, uuuu h:mm a z", Locale.US)
                .withZone(zoneId);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("playtime.seen")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        // /seen -> self (if player)
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(PREFIX + ChatColor.RED + "Usage: /" + label + " <player>");
                return true;
            }
            showSeen(sender, player.getUniqueId(), player.getName(), true);
            return true;
        }

        // /seen <player>
        String targetName = args[0];

        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            showSeen(sender, online.getUniqueId(), online.getName(), true);
            return true;
        }

        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(targetName);
        if (offline == null || offline.getUniqueId() == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Player '" + targetName + "' has never joined.");
            return true;
        }

        showSeen(sender, offline.getUniqueId(), offline.getName() != null ? offline.getName() : targetName, false);
        return true;
    }

    private void showSeen(CommandSender sender, UUID uuid, String name, boolean isOnline) {
        if (isOnline) {
            sender.sendMessage(PREFIX + ChatColor.AQUA + name + ChatColor.YELLOW + " is currently online.");
            return;
        }

        Optional<Instant> lastJoinOpt = repository.getLastJoin(uuid);
        if (lastJoinOpt.isEmpty()) {
            sender.sendMessage(PREFIX + ChatColor.RED + "No last login record found for " + name + ".");
            return;
        }

        Instant lastJoin = lastJoinOpt.get();
        Instant now = Instant.now();
        long agoMillis = Math.max(0L, Duration.between(lastJoin, now).toMillis());

        String ago = TimeFormats.formatDurationMillis(agoMillis);
        String when = formatter.format(lastJoin.atZone(zoneId));

        String line = ChatColor.GOLD + "[Playtime] " + ChatColor.YELLOW +
                name + ChatColor.GRAY + " | " +
                ChatColor.YELLOW + "Last seen " + ChatColor.AQUA + ago + ChatColor.YELLOW + " ago " +
                ChatColor.GRAY + "(" + ChatColor.WHITE + when + ChatColor.GRAY + ")";

        sender.sendMessage(line);
    }

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

