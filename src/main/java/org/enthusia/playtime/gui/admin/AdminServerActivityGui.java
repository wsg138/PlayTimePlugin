package org.enthusia.playtime.gui.admin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.bedrock.BedrockSupport;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.model.RangeTotals;

import java.io.File;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.logging.Level;

public final class AdminServerActivityGui implements InventoryHolder {

    private final PlayTimePlugin plugin;
    private final Player viewer;
    private final PlaytimeRepository repository;
    private final boolean bedrock;
    private Range currentRange;
    private Inventory inventory;

    public AdminServerActivityGui(PlayTimePlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.repository = plugin.getRepository();
        BedrockSupport bs = plugin.getBedrockSupport();
        this.bedrock = bs != null && bs.isBedrock(viewer);
        this.currentRange = Range.ALL; // default
    }

    public void open() {
        buildInventory();
        viewer.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    // --- Range enum ---

    private enum Range {
        TODAY("Today"),
        SEVEN_DAYS("Last 7 days"),
        THIRTY_DAYS("Last 30 days"),
        ALL("All time");

        private final String label;

        Range(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    // --- Stats container ---

    private static final class ServerStats {
        long playersWithPlaytime;
        long totalMinutes;
        long activeMinutes;
        long afkMinutes;

        long uniqueJoins;
        long totalJoins;

        long newPlayers;
        long returningPlayers;
        long retainedNewPlayers;

        long avgUniquePlayersPerDay;
        long maxUniquePlayersPerDay;
    }

    // --- Build GUI ---

    private void buildInventory() {
        String title = ChatColor.DARK_BLUE + "Playtime - Server Activity (" + currentRange.getLabel() + ")";
        this.inventory = Bukkit.createInventory(this, 54, title);

        // Filler
        if (!bedrock) {
            ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta fm = filler.getItemMeta();
            fm.setDisplayName(" ");
            filler.setItemMeta(fm);

            for (int i = 0; i < 54; i++) {
                inventory.setItem(i, filler);
            }
        }

        // Load stats
        ServerStats stats = loadStats(currentRange);

        // --- Range selector row (row 2) ---
        // Slots: 10 (TODAY), 12 (7d), 14 (30d), 16 (ALL)
        inventory.setItem(10, rangeItem(Range.TODAY,
                Material.FILLED_MAP,
                currentRange == Range.TODAY));
        inventory.setItem(12, rangeItem(Range.SEVEN_DAYS,
                Material.NETHER_STAR,
                currentRange == Range.SEVEN_DAYS));
        inventory.setItem(14, rangeItem(Range.THIRTY_DAYS,
                Material.PAPER,
                currentRange == Range.THIRTY_DAYS));
        inventory.setItem(16, rangeItem(Range.ALL,
                Material.LEATHER_CHESTPLATE,
                currentRange == Range.ALL));

        // Derived metrics
        double afkPct = stats.totalMinutes > 0
                ? (stats.afkMinutes * 100.0 / stats.totalMinutes)
                : 0.0;
        long avgMinutesPerPlayer = stats.playersWithPlaytime > 0
                ? stats.totalMinutes / stats.playersWithPlaytime
                : 0;
        double retentionPct = stats.newPlayers > 0
                ? (stats.retainedNewPlayers * 100.0 / stats.newPlayers)
                : 0.0;
        double avgJoinsPerPlayer = stats.uniqueJoins > 0
                ? (stats.totalJoins * 1.0 / stats.uniqueJoins)
                : 0.0;

        // --- Players & playtime summary (slot 20) ---
        List<String> playtimeLore = new ArrayList<>();
        playtimeLore.add(ChatColor.YELLOW + "Players with playtime: " + ChatColor.AQUA + stats.playersWithPlaytime);
        playtimeLore.add("");
        playtimeLore.add(ChatColor.GRAY + "Total minutes: " + ChatColor.AQUA + formatMinutes(stats.totalMinutes));
        playtimeLore.add(ChatColor.GRAY + "Active minutes: " + ChatColor.GREEN + formatMinutes(stats.activeMinutes));
        playtimeLore.add(ChatColor.GRAY + "AFK minutes: " + ChatColor.RED + formatMinutes(stats.afkMinutes));
        if (stats.playersWithPlaytime > 0) {
            playtimeLore.add("");
            playtimeLore.add(ChatColor.YELLOW + "Avg minutes/player: " +
                    ChatColor.AQUA + formatMinutes(avgMinutesPerPlayer));
            playtimeLore.add(ChatColor.YELLOW + "AFK ratio: " +
                    ChatColor.AQUA + formatPercent(afkPct));
        }
        if (stats.avgUniquePlayersPerDay > 0) {
            playtimeLore.add("");
            playtimeLore.add(ChatColor.YELLOW + "Avg unique players/day: " +
                    ChatColor.AQUA + stats.avgUniquePlayersPerDay);
            playtimeLore.add(ChatColor.YELLOW + "Peak unique/day: " +
                    ChatColor.AQUA + stats.maxUniquePlayersPerDay);
        }

        inventory.setItem(20, buildStatsItem(
                Material.PLAYER_HEAD,
                ChatColor.GOLD + "Players & Playtime",
                playtimeLore
        ));

        // --- Joins summary (slot 22) ---
        List<String> joinsLore = new ArrayList<>();
        joinsLore.add(ChatColor.YELLOW + "Unique players joined: " +
                ChatColor.AQUA + stats.uniqueJoins);
        joinsLore.add(ChatColor.YELLOW + "Total joins: " +
                ChatColor.AQUA + stats.totalJoins);
        if (stats.uniqueJoins > 0) {
            joinsLore.add("");
            joinsLore.add(ChatColor.YELLOW + "Avg joins/player: " +
                    ChatColor.AQUA + String.format(Locale.US, "%.2f", avgJoinsPerPlayer));
        } else {
            joinsLore.add("");
            joinsLore.add(ChatColor.GRAY + "No recorded joins in this period.");
        }
        joinsLore.add("");
        joinsLore.add(ChatColor.DARK_GRAY + "Includes all logins in the range.");

        inventory.setItem(22, buildStatsItem(
                Material.OAK_DOOR,
                ChatColor.GOLD + "Joins",
                joinsLore
        ));

        // --- New vs returning (slot 24) ---
        List<String> newRetLore = new ArrayList<>();
        newRetLore.add(ChatColor.YELLOW + "New players: " + ChatColor.AQUA + stats.newPlayers);
        newRetLore.add(ChatColor.YELLOW + "Returning players: " + ChatColor.AQUA + stats.returningPlayers);
        newRetLore.add("");
        newRetLore.add(ChatColor.GRAY + "New players who came back:");
        newRetLore.add(ChatColor.AQUA + "  " + stats.retainedNewPlayers + " / " + stats.newPlayers);
        if (stats.newPlayers > 0) {
            newRetLore.add(ChatColor.YELLOW + "Retention: " +
                    ChatColor.AQUA + formatPercent(retentionPct));
        } else {
            newRetLore.add(ChatColor.GRAY + "Retention: " + ChatColor.DARK_GRAY + "N/A");
        }

        inventory.setItem(24, buildStatsItem(
                Material.EMERALD,
                ChatColor.GOLD + "New vs Returning",
                newRetLore
        ));

        // --- Explainer / help (slot 31) ---
        inventory.setItem(31, buildStatsItem(
                Material.BOOK,
                ChatColor.GOLD + "How to read this",
                List.of(
                        ChatColor.GRAY + "Range: " + ChatColor.AQUA + currentRange.getLabel(),
                        "",
                        ChatColor.GRAY + "New players: first visit",
                        ChatColor.GRAY + " in this period.",
                        ChatColor.GRAY + "Returning: visited before",
                        ChatColor.GRAY + " the period, but joined now.",
                        "",
                        ChatColor.DARK_GRAY + "Retention = new players",
                        ChatColor.DARK_GRAY + " who log in again later."
                )
        ));

        // --- Bottom row: back + close ---

        ItemStack back = new ItemStack(Material.OAK_DOOR);
        ItemMeta bm = back.getItemMeta();
        bm.setDisplayName(ChatColor.YELLOW + "Back");
        back.setItemMeta(bm);

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta cm = close.getItemMeta();
        cm.setDisplayName(ChatColor.RED + "Close");
        close.setItemMeta(cm);

        // 48 = back, 50 = close (symmetry with gap)
        inventory.setItem(48, back);
        inventory.setItem(50, close);
    }

    private ItemStack rangeItem(Range range, Material material, boolean selected) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

        String name = (selected ? ChatColor.GREEN + "● " : ChatColor.YELLOW + "○ ") + range.getLabel();
        meta.setDisplayName(name);

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Click to view:");
        lore.add(ChatColor.AQUA + range.getLabel());
        if (selected) {
            lore.add("");
            lore.add(ChatColor.GREEN + "Currently selected");
        }
        meta.setLore(lore);

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildStatsItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private String formatMinutes(long minutes) {
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

    private String formatPercent(double value) {
        return String.format(Locale.US, "%.1f%%", value);
    }

    // --- Click handling (called by AdminGuiClickListener) ---

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);

        if (event.getWhoClicked() != viewer) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }

        // Range selector
        if (slot == 10) {
            currentRange = Range.TODAY;
            reopen();
            return;
        }
        if (slot == 12) {
            currentRange = Range.SEVEN_DAYS;
            reopen();
            return;
        }
        if (slot == 14) {
            currentRange = Range.THIRTY_DAYS;
            reopen();
            return;
        }
        if (slot == 16) {
            currentRange = Range.ALL;
            reopen();
            return;
        }

        // Back / close
        if (slot == 48) {
            viewer.closeInventory();
            new AdminMainGui(plugin, viewer).open();
            return;
        }
        if (slot == 50) {
            viewer.closeInventory();
        }
    }

    private void reopen() {
        buildInventory();
        viewer.openInventory(inventory);
    }

    // --- Helper to map Range -> repository key ("ALL", "TODAY", "7D", "30D") ---

    private String toRangeKey(Range range) {
        return switch (range) {
            case TODAY -> "TODAY";
            case SEVEN_DAYS -> "7D";
            case THIRTY_DAYS -> "30D";
            case ALL -> "ALL";
        };
    }

    // --- DB + stats loading ---

    private ServerStats loadStats(Range range) {
        ServerStats stats = new ServerStats();

        String type = plugin.getConfig().getString("storage.type", "sqlite").toLowerCase(Locale.ROOT);
        ZoneId zone = ZoneId.of(plugin.getConfig().getString("joins.timezone", "America/New_York"));

        LocalDate today = LocalDate.now(zone);
        LocalDate fromDate;
        LocalDate toDate = today;

        switch (range) {
            case TODAY -> fromDate = today;
            case SEVEN_DAYS -> fromDate = today.minusDays(6);
            case THIRTY_DAYS -> fromDate = today.minusDays(29);
            case ALL -> fromDate = null;
            default -> fromDate = null;
        }

        long fromEpoch;
        long toEpoch;
        if (fromDate == null) {
            fromEpoch = 0L;
            toEpoch = Long.MAX_VALUE;
        } else {
            fromEpoch = fromDate.atStartOfDay(zone).toInstant().toEpochMilli();
            toEpoch = toDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        }

        try (Connection conn = openConnection(type)) {

            // --- Playtime stats ---

            if (fromDate == null) {
                // ALL time -> use lifetime_agg
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT COUNT(*) AS players, " +
                                     "COALESCE(SUM(total_minutes),0) AS total, " +
                                     "COALESCE(SUM(active_minutes),0) AS active, " +
                                     "COALESCE(SUM(afk_minutes),0) AS afk " +
                                     "FROM lifetime_agg"
                     )) {
                    if (rs.next()) {
                        stats.playersWithPlaytime = rs.getLong("players");
                        stats.totalMinutes = rs.getLong("total");
                        stats.activeMinutes = rs.getLong("active");
                        stats.afkMinutes = rs.getLong("afk");
                    }
                }
            } else {
                // Range views -> aggregate via repository.getRangeTotals for each player
                String rangeKey = toRangeKey(range);
                Instant now = Instant.now();

                // Use lifetime_agg to get all players that have playtime
                try (Statement stPlayers = conn.createStatement();
                     ResultSet rsPlayers = stPlayers.executeQuery(
                             "SELECT player_uuid AS uuid FROM lifetime_agg"
                     )) {
                    while (rsPlayers.next()) {
                        String uuidStr = rsPlayers.getString("uuid");
                        if (uuidStr == null || uuidStr.isEmpty()) continue;

                        UUID uuid;
                        try {
                            uuid = UUID.fromString(uuidStr);
                        } catch (IllegalArgumentException ex) {
                            continue; // ignore bad rows
                        }

                        RangeTotals totals = repository.getRangeTotals(uuid, now, rangeKey);
                        if (totals == null) continue;

                        long total = totals.totalMinutes;
                        long active = totals.activeMinutes;
                        long afk = totals.afkMinutes;

                        if (total > 0 || active > 0 || afk > 0) {
                            stats.playersWithPlaytime++;
                            stats.totalMinutes += total;
                            stats.activeMinutes += active;
                            stats.afkMinutes += afk;
                        }
                    }
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.WARNING,
                            "[EnthusiaPlaytime] Failed to aggregate range playtime for " + range, ex);
                }
            }

            // --- Joins + daily uniques in one pass ---

            Map<String, Long> earliestJoin = new HashMap<>();
            Map<String, Integer> totalJoinsByPlayer = new HashMap<>();
            Map<String, Integer> joinsInRangeByPlayer = new HashMap<>();
            Map<LocalDate, Set<String>> dailyUniques = new HashMap<>();

            String joinQuery = "SELECT uuid, join_ts FROM joins_log";
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(joinQuery)) {
                while (rs.next()) {
                    String uuidStr = rs.getString("uuid");
                    if (uuidStr == null || uuidStr.isEmpty()) {
                        continue;
                    }

                    long ts = rs.getLong("join_ts");

                    // Earliest join overall (plugin lifetime)
                    Long prev = earliestJoin.get(uuidStr);
                    if (prev == null || ts < prev) {
                        earliestJoin.put(uuidStr, ts);
                    }

                    // Total joins overall
                    totalJoinsByPlayer.merge(uuidStr, 1, Integer::sum);

                    // In-range joins
                    if (ts >= fromEpoch && ts < toEpoch) {
                        joinsInRangeByPlayer.merge(uuidStr, 1, Integer::sum);
                        stats.totalJoins++;
                    }

                    // Per-day uniques (only for ranged views)
                    if (fromDate != null && ts >= fromEpoch && ts < toEpoch) {
                        Instant instant = Instant.ofEpochMilli(ts);
                        LocalDate date = instant.atZone(zone).toLocalDate();
                        dailyUniques
                                .computeIfAbsent(date, d -> new HashSet<>())
                                .add(uuidStr);
                    }
                }
            }

            // Unique joins in the period
            stats.uniqueJoins = joinsInRangeByPlayer.size();

            // Use join-based uniques as "players with playtime" fallback when no playtime rows
            if (stats.playersWithPlaytime == 0 && stats.uniqueJoins > 0) {
                stats.playersWithPlaytime = stats.uniqueJoins;
            }

            // Daily unique players (for ranges only)
            if (!dailyUniques.isEmpty()) {
                long sum = 0;
                long max = 0;
                for (Set<String> set : dailyUniques.values()) {
                    long c = set.size();
                    sum += c;
                    if (c > max) {
                        max = c;
                    }
                }
                stats.avgUniquePlayersPerDay = Math.round(sum * 1.0 / dailyUniques.size());
                stats.maxUniquePlayersPerDay = max;
            }

            // --- New vs returning vs retained ---

            if (fromDate == null) {
                // ALL-TIME:
                // - newPlayers = every unique player ever seen
                // - returningPlayers = players with 2+ joins
                // - retainedNewPlayers = same as returningPlayers
                for (Map.Entry<String, Integer> entry : totalJoinsByPlayer.entrySet()) {
                    int total = entry.getValue();
                    stats.newPlayers++;
                    if (total > 1) {
                        stats.returningPlayers++;
                        stats.retainedNewPlayers++;
                    }
                }
            } else {
                // RANGED (Today / 7d / 30d):
                // - newPlayers = first-ever join inside [fromEpoch, toEpoch)
                // - returningPlayers = had joins before fromEpoch & joined in range
                // - retainedNewPlayers = newPlayers with 2+ joins total
                for (String uuidStr : joinsInRangeByPlayer.keySet()) {
                    long earliest = earliestJoin.getOrDefault(uuidStr, Long.MAX_VALUE);
                    boolean isNewInRange = (earliest >= fromEpoch && earliest < toEpoch);
                    if (isNewInRange) {
                        stats.newPlayers++;
                        int totalAllTime = totalJoinsByPlayer.getOrDefault(uuidStr, 0);
                        if (totalAllTime > 1) {
                            stats.retainedNewPlayers++;
                        }
                    } else {
                        stats.returningPlayers++;
                    }
                }
            }

        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[EnthusiaPlaytime] Failed to load server activity stats for " + currentRange, ex);
        }

        return stats;
    }

    private Connection openConnection(String type) throws SQLException {
        type = type.toLowerCase(Locale.ROOT);
        if (type.equals("mysql")) {
            String host = plugin.getConfig().getString("storage.mysql.host", "localhost");
            int port = plugin.getConfig().getInt("storage.mysql.port", 3306);
            String dbName = plugin.getConfig().getString("storage.mysql.database", "playtime");
            String user = plugin.getConfig().getString("storage.mysql.username", "root");
            String pass = plugin.getConfig().getString("storage.mysql.password", "password");
            boolean useSsl = plugin.getConfig().getBoolean("storage.mysql.use-ssl", false);
            String url = "jdbc:mysql://" + host + ":" + port + "/" + dbName +
                    "?useSSL=" + useSsl + "&autoReconnect=true";
            return DriverManager.getConnection(url, user, pass);
        } else {
            String fileName = plugin.getConfig().getString("storage.sqlite.file", "playtime.db");
            File dbFile = new File(plugin.getDataFolder(), fileName);
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            return DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        }
    }
}
