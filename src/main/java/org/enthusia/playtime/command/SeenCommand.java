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
    private static final int UNIQUE_MATCH_COUNT = 1;
    private static final int TARGET_ARG_COUNT = 1;

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

        showTargetSeen(sender, runtime, args[0]);
        return true;
    }

    private void showTargetSeen(CommandSender sender, PlaytimeRuntime runtime, String targetName) {
        UUID requestedUuid = null;
        try {
            requestedUuid = UUID.fromString(targetName);
        } catch (IllegalArgumentException ignored) {
            // Most lookups use a username.
        }
        UUID uuid = requestedUuid;
        Player online = uuid == null ? Bukkit.getPlayerExact(targetName) : Bukkit.getPlayer(uuid);
        OfflinePlayer offline = uuid == null ? Bukkit.getOfflinePlayerIfCached(targetName) : null;
        UUID fallback = online != null ? online.getUniqueId()
                : offline != null ? offline.getUniqueId() : runtime.headCache().findUuidByName(targetName);
        String fallbackName = online != null ? online.getName()
                : offline != null && offline.getName() != null ? offline.getName() : targetName;
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    var matches = uuid == null ? runtime.repository().findNameObservations(targetName) : List.<PlaytimeRepository.NameObservation>of();
                    var ids = new ArrayList<>(matches.stream().map(PlaytimeRepository.NameObservation::uuid).distinct().toList());
                    if (uuid == null && online != null && !ids.contains(online.getUniqueId())) {
                        ids.add(online.getUniqueId());
                    }
                    if (ids.size() > UNIQUE_MATCH_COUNT) {
                        var labels = new ArrayList<String>();
                        for (UUID match : ids) {
                            var history = runtime.repository().getNameObservations(match);
                            String latest = history.isEmpty() ? targetName : history.get(0).username();
                            labels.add(latest + " (" + match + ")");
                        }
                        deliver(sender, runtime, () -> sender.sendMessage(PREFIX + ChatColor.YELLOW
                                + "Multiple players used " + targetName + ": " + String.join(", ", labels)
                                + ". Use /seen <UUID>."));
                        return;
                    }
                    UUID resolved = uuid != null ? uuid : ids.size() == UNIQUE_MATCH_COUNT ? ids.get(0) : fallback;
                    if (resolved == null) {
                        deliver(sender, runtime, () -> sender.sendMessage(PREFIX + ChatColor.RED
                                + "No player found for '" + targetName + "'."));
                        return;
                    }
                    var history = runtime.repository().getNameObservations(resolved);
                    Optional<Instant> lastSeen = runtime.repository().getLastSeen(resolved);
                    String name = history.isEmpty() ? fallbackName : history.get(0).username();
                    deliver(sender, runtime, () -> {
                        Player current = Bukkit.getPlayer(resolved);
                        if (current != null && current.isOnline()) {
                            String liveName = current.getName();
                            sender.sendMessage(PREFIX + ChatColor.AQUA + liveName + ChatColor.YELLOW + " is currently online.");
                            showHistory(sender, runtime, history, liveName);
                        } else {
                            sendSeen(sender, runtime, name, lastSeen);
                            showHistory(sender, runtime, history, name);
                        }
                    });
                } catch (Exception failure) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Failed to load username history", failure);
                    deliver(sender, runtime, () -> sender.sendMessage(PREFIX + ChatColor.RED + "Could not load player history. Try again later."));
                }
            });
        } catch (IllegalPluginAccessException exception) {
            if (plugin.isEnabled()) throw exception;
        }
    }

    private void showSeen(CommandSender sender, PlaytimeRuntime runtime, UUID uuid, String name, boolean online) {
        showTargetSeen(sender, runtime, uuid.toString());
    }

    private void deliver(CommandSender sender, PlaytimeRuntime runtime, Runnable result) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.runtime() == runtime && (!(sender instanceof Player player) || player.isOnline())) result.run();
            });
        } catch (IllegalPluginAccessException exception) {
            if (plugin.isEnabled()) throw exception;
        }
    }

    private void showHistory(CommandSender sender, PlaytimeRuntime runtime,
                             List<PlaytimeRepository.NameObservation> history, String currentName) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US)
                .withZone(runtime.config().joins().zoneId());
        var previous = history.stream().filter(row -> !row.username().equalsIgnoreCase(currentName))
                .map(row -> row.username() + " (observed " + formatter.format(row.firstObserved())
                        + " - " + formatter.format(row.lastObserved()) + ")").toList();
        if (!previous.isEmpty()) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "Previously seen as: " + ChatColor.AQUA + String.join(", ", previous));
        }
    }

    private void sendSeen(CommandSender sender, PlaytimeRuntime runtime, String name, Optional<Instant> lastSeenOpt) {
        PlaytimeRuntime current = plugin.runtime();
        if (current != runtime) {
            return;
        }
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
