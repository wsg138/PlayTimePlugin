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
import org.enthusia.playtime.data.model.LeaderboardEntry;
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.gui.LeaderboardGui;
import org.enthusia.playtime.gui.PlaytimeMainGui;
import org.enthusia.playtime.gui.admin.AdminMainGui;
import org.enthusia.playtime.gui.admin.AdminPlayersGui;
import org.enthusia.playtime.gui.admin.AdminServerActivityGui;
import org.enthusia.playtime.service.PlaytimeRuntime;
import org.enthusia.playtime.util.NumeralTierCatalog;
import org.enthusia.playtime.util.TierColorFormatter;
import org.enthusia.playtime.util.TimeFormats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class PlaytimeCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.GOLD + "[Playtime] " + ChatColor.YELLOW;
    private static final int NO_ARGS = 0;
    private static final int FIRST_ARG_COUNT = 1;
    private static final int SECOND_ARG_COUNT = 2;
    private static final int THIRD_ARG_COUNT = 3;
    private static final int FOURTH_ARG_COUNT = 4;
    private static final int FIRST_PAGE = 1;
    private static final int CONSOLE_PAGE_SIZE = 10;
    private static final String BASE_PERMISSION = "playtime.base";
    private static final String ADMIN_COMMAND = "admin";
    private static final String TOP_COMMAND = "top";
    private static final String NUMERALS_COMMAND = "numerals";
    private static final List<String> TOP_METRICS = List.of("active", "afk", "total");
    private static final List<String> TOP_RANGES = List.of("today", "7d", "30d", "all");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("players", "activity", "reload", "debug", "performance");

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

        if (hasSubcommand(args, ADMIN_COMMAND)) {
            return handleAdmin(sender, label, args);
        }

        if (hasSubcommand(args, TOP_COMMAND)) {
            handleTop(sender, runtime, args);
            return true;
        }

        if (hasSubcommand(args, NUMERALS_COMMAND)) {
            showNumerals(sender, runtime);
            return true;
        }

        if (args.length == NO_ARGS) {
            return handleOwnPlaytime(sender, label, runtime);
        }

        return handleTargetPlaytime(sender, runtime, args[0]);
    }

    private boolean hasSubcommand(String[] args, String subcommand) {
        return args.length >= FIRST_ARG_COUNT && args[0].equalsIgnoreCase(subcommand);
    }

    private boolean handleOwnPlaytime(CommandSender sender, String label, PlaytimeRuntime runtime) {
        if (!(sender instanceof Player player)) {
            send(sender, ChatColor.RED + "Usage: /" + label + " <player|top|admin|numerals>");
            return true;
        }
        if (!sender.hasPermission(BASE_PERMISSION)) {
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

    private boolean handleTargetPlaytime(CommandSender sender, PlaytimeRuntime runtime, String targetName) {
        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            if (!canViewTarget(sender, online.getUniqueId())) return true;
            showPlaytime(sender, runtime, online.getUniqueId(), online.getName());
            return true;
        }

        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(targetName);
        UUID cachedUuid = runtime.headCache().findUuidByName(targetName);

        if (offline == null || offline.getUniqueId() == null) {
            if (cachedUuid != null) {
                if (!canViewTarget(sender, cachedUuid)) return true;
                showPlaytime(sender, runtime, cachedUuid, targetName);
                return true;
            }
            send(sender, ChatColor.RED + "Player '" + targetName + "' has never joined.");
            return true;
        }

        if (!canViewTarget(sender, offline.getUniqueId())) return true;
        showPlaytime(sender, runtime, offline.getUniqueId(), displayName(offline, targetName));
        return true;
    }

    private boolean canViewTarget(CommandSender sender, UUID targetId) {
        if (sender instanceof Player player && player.getUniqueId().equals(targetId)) {
            return true;
        }
        if (sender.hasPermission("playtime.others")) {
            return true;
        }
        send(sender, ChatColor.RED + "You don't have permission to view others' playtime.");
        return false;
    }

    private String displayName(OfflinePlayer offline, String fallback) {
        return offline.getName() != null ? offline.getName() : fallback;
    }

    private boolean handleAdmin(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("playtime.admin.base")) {
            send(sender, ChatColor.RED + "You don't have permission to use admin playtime tools.");
            return true;
        }

        if (args.length == FIRST_ARG_COUNT) {
            return openAdminMain(sender, label);
        }

        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "players" -> openAdminPlayers(sender);
            case "activity" -> openAdminActivity(sender);
            case "reload" -> reloadRuntime(sender);
            case "debug" -> showDebug(sender);
            case "performance" -> showPerformance(sender);
            default -> {
                send(sender, "Usage: /" + label + " admin [players|activity|reload|debug|performance]");
                yield true;
            }
        };
    }

    private boolean openAdminMain(CommandSender sender, String label) {
        if (!(sender instanceof Player player)) {
            send(sender, "Usage: /" + label + " admin <players|activity|reload|debug|performance>");
            return true;
        }
        new AdminMainGui(plugin, player).open();
        return true;
    }

    private boolean openAdminPlayers(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "This admin GUI is in-game only.");
            return true;
        }
        new AdminPlayersGui(plugin, player).open();
        return true;
    }

    private boolean openAdminActivity(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "This admin GUI is in-game only.");
            return true;
        }
        new AdminServerActivityGui(plugin, player).open();
        return true;
    }

    private boolean reloadRuntime(CommandSender sender) {
        if (!sender.hasPermission("playtime.admin.reload")) {
            send(sender, ChatColor.RED + "You don't have permission to reload.");
            return true;
        }
        if (plugin.reloadPluginRuntime()) {
            send(sender, ChatColor.GREEN + "Playtime plugin reloaded safely.");
        } else {
            send(sender, ChatColor.RED + "Reload failed. Check console; a server restart may be needed if runtime startup failed.");
        }
        return true;
    }

    private boolean showDebug(CommandSender sender) {
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

    private boolean showPerformance(CommandSender sender) {
        if (!sender.hasPermission("playtime.admin.debug")) {
            send(sender, ChatColor.RED + "You don't have permission to view performance counters.");
            return true;
        }
        PlaytimeRuntime runtime = plugin.runtime();
        if (runtime == null) {
            send(sender, ChatColor.RED + "Playtime runtime is not available.");
            return true;
        }
        send(sender, ChatColor.YELLOW + runtime.performanceSummary());
        return true;
    }

    private void handleTop(CommandSender sender, PlaytimeRuntime runtime, String[] args) {
        if (!sender.hasPermission(BASE_PERMISSION)) {
            send(sender, ChatColor.RED + "You don't have permission to view playtime leaderboards.");
            return;
        }

        TopRequest request = parseTopRequest(sender, runtime, args);
        if (request == null) {
            return;
        }

        if (sender instanceof Player player) {
            new LeaderboardGui(plugin, player, request.metric().toUpperCase(Locale.ROOT), request.range().toUpperCase(Locale.ROOT), request.page()).open();
            return;
        }

        int offset = (request.page() - 1) * CONSOLE_PAGE_SIZE;
        List<LeaderboardEntry> rows = runtime.readService().getLeaderboard(request.metric().toUpperCase(Locale.ROOT), request.range().toUpperCase(Locale.ROOT), CONSOLE_PAGE_SIZE, offset);
        if (rows.isEmpty()) {
            send(sender, runtime.readService().isLoading()
                    ? ChatColor.YELLOW + "Leaderboard cache is refreshing. Try again in a moment."
                    : ChatColor.RED + "No leaderboard data for that metric/range yet.");
            return;
        }

        send(sender, niceMetric(request.metric()) + " leaderboard (" + niceRange(request.range()) + "), page " + request.page() + ":");
        for (LeaderboardEntry entry : rows) {
            sender.sendMessage(consoleLeaderboardLine(entry));
        }
    }

    private TopRequest parseTopRequest(CommandSender sender, PlaytimeRuntime runtime, String[] args) {
        String metric = runtime.config().leaderboards().defaultMetric().toLowerCase(Locale.ROOT);
        String range = runtime.config().leaderboards().defaultRange().toLowerCase(Locale.ROOT);
        int page = 1;
        if (args.length >= SECOND_ARG_COUNT) {
            metric = args[1].toLowerCase(Locale.ROOT);
            if (!TOP_METRICS.contains(metric)) {
                send(sender, ChatColor.RED + "Unknown metric '" + metric + "'. Use active/afk/total.");
                return null;
            }
        }
        if (args.length >= THIRD_ARG_COUNT) {
            range = args[2].toLowerCase(Locale.ROOT);
            if (!TOP_RANGES.contains(range)) {
                send(sender, ChatColor.RED + "Unknown range '" + range + "'. Use today/7d/30d/all.");
                return null;
            }
        }
        if (args.length >= FOURTH_ARG_COUNT) {
            page = parseTopPage(sender, args[3]);
            if (page < FIRST_PAGE) {
                return null;
            }
        }
        return new TopRequest(metric, range, page);
    }

    private int parseTopPage(CommandSender sender, String rawPage) {
        try {
            return Math.max(FIRST_PAGE, Integer.parseInt(rawPage));
        } catch (NumberFormatException exception) {
            send(sender, ChatColor.RED + "Page must be a number.");
            return 0;
        }
    }

    private String consoleLeaderboardLine(LeaderboardEntry entry) {
        String name = resolveName(entry.uuid);
        return ChatColor.GRAY + "#" + entry.rank + " "
                + ChatColor.AQUA + name + ChatColor.GRAY + " - "
                + ChatColor.YELLOW + TimeFormats.formatMinutes(entry.totalMinutes)
                + ChatColor.GRAY + " (A: " + ChatColor.GREEN + TimeFormats.formatMinutes(entry.activeMinutes)
                + ChatColor.GRAY + ", AFK: " + ChatColor.RED + TimeFormats.formatMinutes(entry.afkMinutes) + ChatColor.GRAY + ")";
    }

    private void showPlaytime(CommandSender sender, PlaytimeRuntime runtime, UUID uuid, String name) {
        Optional<PlaytimeSnapshot> optional = runtime.readService().getLifetime(uuid);
        if (optional.isEmpty()) {
            send(sender, runtime.readService().isLoading()
                    ? ChatColor.YELLOW + "Playtime cache is refreshing for " + name + ". Try again in a moment."
                    : ChatColor.RED + "No playtime recorded for " + name + ".");
            return;
        }

        PlaytimeSnapshot snapshot = optional.get();
        send(sender, "Playtime for " + name + ":");
        sender.sendMessage(ChatColor.GRAY + "Total: " + ChatColor.AQUA + TimeFormats.formatMinutes(snapshot.totalMinutes)
                + ChatColor.GRAY + " (Active: " + ChatColor.GREEN + TimeFormats.formatMinutes(snapshot.activeMinutes)
                + ChatColor.GRAY + ", AFK: " + ChatColor.RED + TimeFormats.formatMinutes(snapshot.afkMinutes) + ChatColor.GRAY + ")");
    }

    private void showNumerals(CommandSender sender, PlaytimeRuntime runtime) {
        if (!sender.hasPermission(BASE_PERMISSION)) {
            send(sender, ChatColor.RED + "You don't have permission.");
            return;
        }

        if (!runtime.config().numerals().enabled()) {
            send(sender, color(runtime.config().numerals().display().disabled()));
            return;
        }
        PlaytimeConfig.NumeralDisplay display = runtime.config().numerals().display();
        send(sender, color(display.header()));
        if (sender instanceof Player player) {
            Optional<PlaytimeSnapshot> optional = runtime.readService().getLifetime(player.getUniqueId());
            if (optional.isPresent()) {
                PlaytimeSnapshot snapshot = optional.get();
                NumeralTierCatalog.Tier tier = runtime.config().numerals().catalog().tierForMinutes(snapshot.activeMinutes).orElse(null);
                sender.sendMessage(color(template(display.currentTier(), snapshot.activeMinutes, tier)));
            } else {
                sender.sendMessage(color(runtime.readService().isLoading() ? display.loading() : display.noData()));
            }
        }

        StringBuilder builder = new StringBuilder();
        for (NumeralTierCatalog.Tier tier : runtime.config().numerals().catalog().tiers()) {
            if (builder.length() > 0) {
                builder.append(display.separator());
            }
            builder.append(template(display.tierEntry(), 0L, tier));
        }
        sender.sendMessage(color(builder.toString()));
    }

    private String template(String value, long activeMinutes, NumeralTierCatalog.Tier tier) {
        String hours = tier == null ? "" : String.valueOf(tier.requiredHours());
        return TierColorFormatter.replaceTierLabelTokens(value, tier)
                .replace("%playtime%", TimeFormats.formatMinutes(activeMinutes))
                .replace("%tier_hours%", hours);
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
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
        if (args.length == FIRST_ARG_COUNT) {
            addRootCompletions(sender, args[0], result);
        } else if (args.length == SECOND_ARG_COUNT && args[0].equalsIgnoreCase(TOP_COMMAND)) {
            addMatches(result, args[1], TOP_METRICS);
        } else if (args.length == THIRD_ARG_COUNT && args[0].equalsIgnoreCase(TOP_COMMAND)) {
            addMatches(result, args[2], TOP_RANGES);
        } else if (args.length == SECOND_ARG_COUNT && args[0].equalsIgnoreCase(ADMIN_COMMAND)) {
            addMatches(result, args[1], ADMIN_SUBCOMMANDS);
        }
        return result;
    }

    private void addRootCompletions(CommandSender sender, String rawPrefix, List<String> result) {
        String prefix = rawPrefix.toLowerCase(Locale.ROOT);
        addIfPermitted(result, prefix, ADMIN_COMMAND, sender.hasPermission("playtime.admin.base"));
        addIfPermitted(result, prefix, TOP_COMMAND, sender.hasPermission(BASE_PERMISSION));
        addIfPermitted(result, prefix, NUMERALS_COMMAND, sender.hasPermission(BASE_PERMISSION));
        addOnlinePlayerMatches(result, prefix);
    }

    private void addIfPermitted(List<String> result, String prefix, String value, boolean permitted) {
        if (permitted && value.startsWith(prefix)) {
            result.add(value);
        }
    }

    private void addOnlinePlayerMatches(List<String> result, String prefix) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(player.getName());
            }
        }
    }

    private void addMatches(List<String> result, String prefix, List<String> values) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value.startsWith(lowerPrefix)) {
                result.add(value);
            }
        }
    }

    private record TopRequest(String metric, String range, int page) {
    }
}
