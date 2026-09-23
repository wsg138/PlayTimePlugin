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
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.service.PlaytimeRuntime;
import org.enthusia.playtime.util.TimeFormats;

import java.sql.SQLException;
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

    public SeenCommand(PlayTimePlugin plugin) { this.plugin = plugin; }

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
        if (args.length == 0 && !(sender instanceof Player)) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Usage: /" + label + " <player>");
            return true;
        }
        String queried = args.length == 0 ? sender.getName() : args[0];
        Player online = args.length == 0 ? (Player) sender : Bukkit.getPlayerExact(queried);
        if (online != null && sender instanceof Player viewer && !viewer.canSee(online)) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Player is unavailable.");
            return true;
        }
        UUID knownId = online == null ? null : online.getUniqueId();
        if (knownId == null) {
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(queried);
            if (cached != null) knownId = cached.getUniqueId();
        }
        if (knownId == null) knownId = runtime.headCache().findUuidByName(queried);
        UUID candidate = knownId;
        if (!plugin.isEnabled()) return true;
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> lookup(sender, runtime, queried, candidate));
        } catch (IllegalPluginAccessException exception) {
            if (plugin.isEnabled()) throw exception;
        }
        return true;
    }

    private void lookup(CommandSender sender, PlaytimeRuntime runtime, String queried, UUID candidate) {
        try {
            Optional<UUID> observed = runtime.repository().findPlayerByObservedName(queried);
            UUID uuid = observed.orElse(candidate);
            if (uuid == null) {
                reply(runtime, () -> sender.sendMessage(PREFIX + ChatColor.RED + "Player '" + queried + "' has never joined."));
                return;
            }
            List<PlaytimeRepository.ObservedName> names = runtime.repository().getObservedNames(uuid);
            String currentName = runtime.repository().getCurrentUsernameStrict(uuid).orElse(queried);
            Optional<Instant> lastSeen = runtime.repository().getLastSeenStrict(uuid);
            reply(runtime, () -> sendSeen(sender, runtime, uuid, currentName, names, lastSeen));
        } catch (PlaytimeRepository.AmbiguousNameException exception) {
            reply(runtime, () -> sender.sendMessage(PREFIX + ChatColor.RED + "That username belongs to multiple server records. Use a current name."));
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed /seen lookup: " + exception.getMessage());
            reply(runtime, () -> sender.sendMessage(PREFIX + ChatColor.RED + "Username history is temporarily unavailable. Try again later."));
        }
    }

    private void reply(PlaytimeRuntime runtime, Runnable message) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.runtime() == runtime) message.run();
            });
        } catch (IllegalPluginAccessException exception) {
            if (plugin.isEnabled()) throw exception;
        }
    }

    private void sendSeen(CommandSender sender, PlaytimeRuntime runtime, UUID uuid, String currentName,
                          List<PlaytimeRepository.ObservedName> names, Optional<Instant> lastSeen) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null && sender instanceof Player viewer && !viewer.canSee(online)) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Player is unavailable.");
            return;
        }
        String current = online != null ? online.getName() : currentName;
        if (online != null) {
            sender.sendMessage(PREFIX + ChatColor.AQUA + current + ChatColor.YELLOW + " is currently online.");
        } else if (lastSeen.isPresent()) {
            Instant seen = lastSeen.get();
            long ago = Math.max(0L, Duration.between(seen, Instant.now()).toMillis());
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM d, uuuu h:mm a z", Locale.US).withZone(runtime.config().joins().zoneId());
            sender.sendMessage(PREFIX + current + ChatColor.GRAY + " | " + ChatColor.YELLOW + "Last seen "
                    + ChatColor.AQUA + TimeFormats.formatDurationMillis(ago) + ChatColor.YELLOW + " ago "
                    + ChatColor.GRAY + "(" + ChatColor.WHITE + formatter.format(seen) + ChatColor.GRAY + ")");
        } else {
            sender.sendMessage(PREFIX + ChatColor.RED + "No last-seen record found for " + current + ".");
        }
        List<String> previous = names.stream().map(PlaytimeRepository.ObservedName::name)
                .filter(name -> !name.equalsIgnoreCase(current)).toList();
        sender.sendMessage(PREFIX + ChatColor.GRAY + "Previous observed names: " + ChatColor.WHITE
                + (previous.isEmpty() ? "none recorded" : String.join(", ", previous)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if ((!(sender instanceof Player viewer) || viewer.canSee(player))
                        && player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) result.add(player.getName());
            }
        }
        return result;
    }
}
