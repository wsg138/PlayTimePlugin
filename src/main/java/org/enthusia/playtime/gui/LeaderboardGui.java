package org.enthusia.playtime.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.bedrock.BedrockSupport;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.model.LeaderboardEntry;
import org.enthusia.playtime.data.model.RangeTotals;
import org.enthusia.playtime.util.HeadUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class LeaderboardGui implements PlaytimeGui {

    private final PlayTimePlugin plugin;
    private final Player viewer;
    private final boolean bedrock;
    private final PlaytimeRepository repository;
    private final Inventory inventory;

    // State
    private String metric; // "TOTAL", "ACTIVE", "AFK"
    private String range;  // "ALL", "TODAY", "7D", "30D"
    private int page;

    // Slots (0-based) according to your layout (6 rows, 54 slots)
    // Row 0: blank, TODAY, blanks, 30D, blank
    private static final int SLOT_RANGE_TODAY = 1;
    private static final int SLOT_RANGE_30D = 7;

    // Row 1: blank, 7D, blank, ACTIVE, TOTAL, AFK, blank, ALL, blank
    private static final int SLOT_RANGE_7D = 10;
    private static final int SLOT_METRIC_ACTIVE = 12;
    private static final int SLOT_METRIC_TOTAL = 13;
    private static final int SLOT_METRIC_AFK = 14;
    private static final int SLOT_RANGE_ALL = 16;

    // Bottom nav row (row 5): arrow, blank, blank, door, book(self), barrier, blank, blank, arrow
    private static final int SLOT_PREV_PAGE = 45;
    private static final int SLOT_BACK = 48;
    private static final int SLOT_SELF = 49;
    private static final int SLOT_CLOSE = 50;
    private static final int SLOT_NEXT_PAGE = 53;

    public LeaderboardGui(PlayTimePlugin plugin,
                          Player viewer,
                          String metric,
                          String range,
                          int page) {
        this.plugin = plugin;
        this.viewer = viewer;
        BedrockSupport bs = plugin.getBedrockSupport();
        this.bedrock = bs != null && bs.isBedrock(viewer);
        this.repository = plugin.getRepository();
        this.metric = normalizeMetric(metric);
        this.range = normalizeRange(range);
        this.page = Math.max(page, 1);

        int rows = 6; // fixed layout
        int size = rows * 9;

        this.inventory = Bukkit.createInventory(
                new PlaytimeGuiHolder(this),
                size,
                ChatColor.DARK_AQUA + "Playtime Leaderboard"
        );

        render();
    }

    private static String normalizeMetric(String metric) {
        if (metric == null) return "TOTAL";
        String m = metric.toUpperCase(Locale.ROOT);
        if (m.equals("ACTIVE") || m.equals("AFK") || m.equals("TOTAL")) {
            return m;
        }
        return "TOTAL";
    }

    private static String normalizeRange(String range) {
        if (range == null) return "ALL";
        String r = range.toUpperCase(Locale.ROOT);
        if (r.equals("TODAY") || r.equals("7D") || r.equals("30D") || r.equals("ALL")) {
            return r;
        }
        return "ALL";
    }

    private static String metricNice(String metric) {
        return switch (metric) {
            case "ACTIVE" -> "Active";
            case "AFK" -> "AFK";
            default -> "Total";
        };
    }

    private static String rangeNice(String range) {
        return switch (range) {
            case "TODAY" -> "Today";
            case "7D" -> "Last 7 days";
            case "30D" -> "Last 30 days";
            default -> "All time";
        };
    }

    private void render() {
        inventory.clear();

        // Filler: gray glass (skip for Bedrock clients so they see a clean background)
        if (!bedrock) {
            ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta fm = filler.getItemMeta();
            fm.setDisplayName(" ");
            filler.setItemMeta(fm);
            for (int i = 0; i < inventory.getSize(); i++) {
                inventory.setItem(i, filler);
            }
        }

        // Metric buttons
        inventory.setItem(SLOT_METRIC_ACTIVE, metricItem("ACTIVE"));
        inventory.setItem(SLOT_METRIC_TOTAL, metricItem("TOTAL"));
        inventory.setItem(SLOT_METRIC_AFK, metricItem("AFK"));

        // Range buttons
        inventory.setItem(SLOT_RANGE_TODAY, rangeItem("TODAY"));
        inventory.setItem(SLOT_RANGE_7D, rangeItem("7D"));
        inventory.setItem(SLOT_RANGE_30D, rangeItem("30D"));
        inventory.setItem(SLOT_RANGE_ALL, rangeItem("ALL"));

        // Entry slots (rows 2,3,4; cols 1–7)
        List<Integer> entrySlots = buildEntrySlots();
        int pageSize = entrySlots.size();

        List<LeaderboardEntry> rows = repository.getLeaderboard(
                metric,
                range,
                Instant.now(),
                pageSize,
                (page - 1) * pageSize
        );

        LeaderboardEntry selfEntry = null;

        for (int i = 0; i < rows.size() && i < entrySlots.size(); i++) {
            LeaderboardEntry entry = rows.get(i);
            int slot = entrySlots.get(i);
            inventory.setItem(slot, entryItem(entry));
            if (entry.uuid.equals(viewer.getUniqueId())) {
                selfEntry = entry;
            }
        }

        // Nav items
        ItemStack prev = new ItemStack(Material.ARROW);
        ItemMeta pm = prev.getItemMeta();
        pm.setDisplayName(ChatColor.YELLOW + "Previous page (" + Math.max(page - 1, 1) + ")");
        prev.setItemMeta(pm);
        inventory.setItem(SLOT_PREV_PAGE, prev);

        ItemStack next = new ItemStack(Material.ARROW);
        ItemMeta nm = next.getItemMeta();
        nm.setDisplayName(ChatColor.YELLOW + "Next page (" + (page + 1) + ")");
        next.setItemMeta(nm);
        inventory.setItem(SLOT_NEXT_PAGE, next);

        ItemStack back = new ItemStack(Material.OAK_DOOR);
        ItemMeta bm = back.getItemMeta();
        bm.setDisplayName(ChatColor.AQUA + "Back to main menu");
        back.setItemMeta(bm);
        inventory.setItem(SLOT_BACK, back);

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta cm = close.getItemMeta();
        cm.setDisplayName(ChatColor.RED + "Close");
        close.setItemMeta(cm);
        inventory.setItem(SLOT_CLOSE, close);

        // Self stats card in bottom center
        inventory.setItem(SLOT_SELF, selfItem(selfEntry));
    }

    private ItemStack metricItem(String metric) {
        Material mat;
        ChatColor color;
        switch (metric) {
            case "ACTIVE" -> {
                mat = Material.LIME_DYE;
                color = ChatColor.GREEN;
            }
            case "AFK" -> {
                mat = Material.RED_DYE;
                color = ChatColor.RED;
            }
            default -> {
                // TOTAL – use something different than clock
                mat = Material.EXPERIENCE_BOTTLE;
                color = ChatColor.GOLD;
            }
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        boolean selected = this.metric.equals(metric);
        meta.setDisplayName((selected ? ChatColor.BOLD.toString() : "") +
                color + metricNice(metric));
        List<String> lore = new ArrayList<>();
        if (selected) {
            lore.add(ChatColor.GREEN + "Selected");
        } else {
            lore.add(ChatColor.YELLOW + "Click to sort by " + metricNice(metric) + ".");
        }
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack rangeItem(String range) {
        Material mat;
        // You asked:
        // TODAY  -> locator map
        // 7D     -> nether star
        // 30D    -> paper
        // ALL    -> clock
        switch (range) {
            case "TODAY" -> mat = Material.MAP;   // 1.20+; change to MAP if you ever need <1.20
            case "7D" -> mat = Material.NETHER_STAR;
            case "30D" -> mat = Material.PAPER;
            default -> mat = Material.CLOCK;              // ALL time
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        boolean selected = this.range.equals(range);
        meta.setDisplayName((selected ? ChatColor.BOLD.toString() : "") +
                ChatColor.AQUA + rangeNice(range));
        List<String> lore = new ArrayList<>();
        if (selected) {
            lore.add(ChatColor.GREEN + "Selected");
        } else {
            lore.add(ChatColor.YELLOW + "Click to switch range.");
        }
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private List<Integer> buildEntrySlots() {
        List<Integer> slots = new ArrayList<>();
        // Rows 2,3,4 (0-based), cols 1–7
        for (int row = 2; row <= 4; row++) {
            for (int col = 1; col <= 7; col++) {
                slots.add(row * 9 + col);
            }
        }
        return slots;
    }

    private ItemStack entryItem(LeaderboardEntry entry) {
        String name = resolveName(entry.uuid);

        // NEW: use cached head (with baked skin) if available; fall back to old HeadUtils.
        ItemStack head;
        if (plugin.getHeadCache() != null) {
            head = plugin.getHeadCache().createHead(entry.uuid);
        } else {
            head = HeadUtils.getPlayerHead(entry.uuid, name);
        }

        ItemMeta meta = head.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "#" + entry.rank + " " + name);

        boolean highlightTotal = metric.equals("TOTAL");
        boolean highlightActive = metric.equals("ACTIVE");
        boolean highlightAfk = metric.equals("AFK");

        List<String> lore = new ArrayList<>();

        // Total
        String totalPrefix = (highlightTotal ? ChatColor.BOLD.toString() : "") + ChatColor.GRAY + "Total: ";
        String totalLine = totalPrefix + ChatColor.YELLOW + formatMinutes(entry.totalMinutes);
        lore.add(totalLine);

        // Active
        String activePrefix = (highlightActive ? ChatColor.BOLD.toString() : "") + ChatColor.GRAY + "Active: ";
        String activeLine = activePrefix + ChatColor.GREEN + formatMinutes(entry.activeMinutes);
        lore.add(activeLine);

        // AFK
        String afkPrefix = (highlightAfk ? ChatColor.BOLD.toString() : "") + ChatColor.GRAY + "AFK: ";
        String afkLine = afkPrefix + ChatColor.RED + formatMinutes(entry.afkMinutes);
        lore.add(afkLine);

        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        head.setItemMeta(meta);
        return head;
    }

    private ItemStack selfItem(LeaderboardEntry selfEntry) {
        ItemStack book = new ItemStack(Material.BOOK);
        ItemMeta meta = book.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "Your stats - " +
                metricNice(metric) + ", " + rangeNice(range));

        boolean highlightTotal = metric.equals("TOTAL");
        boolean highlightActive = metric.equals("ACTIVE");
        boolean highlightAfk = metric.equals("AFK");

        List<String> lore = new ArrayList<>();
        UUID uuid = viewer.getUniqueId();

        RangeTotals totals = repository.getRangeTotals(uuid, Instant.now(), range);

        if (selfEntry != null) {
            lore.add(ChatColor.GRAY + "Rank: " + ChatColor.YELLOW + "#" + selfEntry.rank);
        } else {
            lore.add(ChatColor.GRAY + "Rank: " + ChatColor.DARK_GRAY + "Not on this page");
        }

        // Total
        String totalPrefix = (highlightTotal ? ChatColor.BOLD.toString() : "") + ChatColor.GRAY + "Total: ";
        String totalLine = totalPrefix + ChatColor.YELLOW + formatMinutes(totals.totalMinutes);
        lore.add(totalLine);

        // Active
        String activePrefix = (highlightActive ? ChatColor.BOLD.toString() : "") + ChatColor.GRAY + "Active: ";
        String activeLine = activePrefix + ChatColor.GREEN + formatMinutes(totals.activeMinutes);
        lore.add(activeLine);

        // AFK
        String afkPrefix = (highlightAfk ? ChatColor.BOLD.toString() : "") + ChatColor.GRAY + "AFK: ";
        String afkLine = afkPrefix + ChatColor.RED + formatMinutes(totals.afkMinutes);
        lore.add(afkLine);

        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Compare this with the heads above.");

        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        book.setItemMeta(meta);
        return book;
    }

    private String resolveName(UUID uuid) {
        // 1) Online name wins
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }

        // 2) Name from cached skins.yml (works even after restart)
        String cached = plugin.getHeadCache() != null
                ? plugin.getHeadCache().getLastKnownName(uuid)
                : null;
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        // 3) Bukkit's offline cache
        org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        if (offline.getName() != null) {
            return offline.getName();
        }

        // 4) Absolute last resort: short UUID chunk
        return uuid.toString().substring(0, 8);
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

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @Override
    public Player getViewer() {
        return viewer;
    }

    @Override
    public void open() {
        viewer.openInventory(inventory);
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();

        // Nav
        if (slot == SLOT_PREV_PAGE) {
            if (page > 1) {
                page--;
                render();
            }
            return;
        }
        if (slot == SLOT_NEXT_PAGE) {
            page++;
            render();
            return;
        }
        if (slot == SLOT_BACK) {
            new PlaytimeMainGui(plugin, viewer).open();
            return;
        }
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return;
        }
        if (slot == SLOT_SELF) {
            // Just an info card for now – no action
            return;
        }

        // Metric buttons
        if (slot == SLOT_METRIC_ACTIVE) {
            this.metric = "ACTIVE";
            this.page = 1;
            render();
            return;
        }
        if (slot == SLOT_METRIC_TOTAL) {
            this.metric = "TOTAL";
            this.page = 1;
            render();
            return;
        }
        if (slot == SLOT_METRIC_AFK) {
            this.metric = "AFK";
            this.page = 1;
            render();
            return;
        }

        // Range buttons
        if (slot == SLOT_RANGE_TODAY) {
            this.range = "TODAY";
            this.page = 1;
            render();
            return;
        }
        if (slot == SLOT_RANGE_7D) {
            this.range = "7D";
            this.page = 1;
            render();
            return;
        }
        if (slot == SLOT_RANGE_30D) {
            this.range = "30D";
            this.page = 1;
            render();
            return;
        }
        if (slot == SLOT_RANGE_ALL) {
            this.range = "ALL";
            this.page = 1;
            render();
        }
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
        // nothing special yet
    }
}
