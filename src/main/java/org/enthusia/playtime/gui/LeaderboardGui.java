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
import org.bukkit.plugin.IllegalPluginAccessException;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.model.LeaderboardEntry;
import org.enthusia.playtime.data.model.RangeTotals;
import org.enthusia.playtime.service.PlaytimeRuntime;
import org.enthusia.playtime.util.TimeFormats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class LeaderboardGui implements PlaytimeGui {

    private static final String METRIC_ACTIVE = "ACTIVE";
    private static final String METRIC_TOTAL = "TOTAL";
    private static final String METRIC_AFK = "AFK";
    private static final String RANGE_TODAY = "TODAY";
    private static final String RANGE_7D = "7D";
    private static final String RANGE_30D = "30D";
    private static final String RANGE_ALL = "ALL";

    private final PlayTimePlugin plugin;
    private final Player viewer;
    private final Inventory inventory;

    private String metric;
    private String range;
    private int page;

    private static final int SLOT_RANGE_TODAY = 1;
    private static final int SLOT_RANGE_30D = 7;
    private static final int SLOT_RANGE_7D = 10;
    private static final int SLOT_METRIC_ACTIVE = 12;
    private static final int SLOT_METRIC_TOTAL = 13;
    private static final int SLOT_METRIC_AFK = 14;
    private static final int SLOT_RANGE_ALL = 16;
    private static final int SLOT_PREV_PAGE = 45;
    private static final int SLOT_BACK = 48;
    private static final int SLOT_SELF = 49;
    private static final int SLOT_CLOSE = 50;
    private static final int SLOT_NEXT_PAGE = 53;

    public LeaderboardGui(PlayTimePlugin plugin, Player viewer, String metric, String range, int page) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.metric = normalizeMetric(metric);
        this.range = normalizeRange(range);
        this.page = Math.max(1, page);
        this.inventory = Bukkit.createInventory(new PlaytimeGuiHolder(this), 54, ChatColor.DARK_AQUA + "Playtime Leaderboard");
        render();
    }

    private void render() {
        inventory.clear();
        fillBackground();
        renderControls();

        List<Integer> entrySlots = buildEntrySlots();
        int pageSize = entrySlots.size();
        PlaytimeRuntime runtime = plugin.runtime();
        List<LeaderboardEntry> entries = runtime == null
                ? List.of()
                : runtime.readService().getLeaderboard(metric, range, pageSize, (page - 1) * pageSize);
        if (runtime != null && entries.isEmpty()
                && runtime.readService().isLeaderboardLoading(metric, range, pageSize, (page - 1) * pageSize)) {
            runtime.counters().guiLoadingRenders.increment();
            inventory.setItem(entrySlots.get(0), loadingItem());
            scheduleRefresh();
        }

        LeaderboardEntry selfEntry = null;
        for (int index = 0; index < entries.size() && index < entrySlots.size(); index++) {
            LeaderboardEntry entry = entries.get(index);
            inventory.setItem(entrySlots.get(index), entryItem(entry));
            if (entry.uuid.equals(viewer.getUniqueId())) {
                selfEntry = entry;
            }
        }

        renderFooter();
        inventory.setItem(SLOT_SELF, selfItem(selfEntry));
    }

    private void renderControls() {
        inventory.setItem(SLOT_METRIC_ACTIVE, metricItem(METRIC_ACTIVE));
        inventory.setItem(SLOT_METRIC_TOTAL, metricItem(METRIC_TOTAL));
        inventory.setItem(SLOT_METRIC_AFK, metricItem(METRIC_AFK));
        inventory.setItem(SLOT_RANGE_TODAY, rangeItem(RANGE_TODAY));
        inventory.setItem(SLOT_RANGE_7D, rangeItem(RANGE_7D));
        inventory.setItem(SLOT_RANGE_30D, rangeItem(RANGE_30D));
        inventory.setItem(SLOT_RANGE_ALL, rangeItem(RANGE_ALL));
    }

    private void renderFooter() {
        inventory.setItem(SLOT_PREV_PAGE, namedItem(Material.ARROW, ChatColor.YELLOW + "Previous page (" + Math.max(page - 1, 1) + ")"));
        inventory.setItem(SLOT_NEXT_PAGE, namedItem(Material.ARROW, ChatColor.YELLOW + "Next page (" + (page + 1) + ")"));
        inventory.setItem(SLOT_BACK, namedItem(Material.OAK_DOOR, ChatColor.AQUA + "Back to main menu"));
        inventory.setItem(SLOT_CLOSE, namedItem(Material.BARRIER, ChatColor.RED + "Close"));
    }

    private ItemStack namedItem(Material material, String displayName) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(displayName);
        item.setItemMeta(meta);
        return item;
    }

    private void fillBackground() {
        PlaytimeRuntime runtime = plugin.runtime();
        boolean bedrock = runtime != null && plugin.getBedrockSupport() != null && plugin.getBedrockSupport().isBedrock(viewer);
        if (bedrock) {
            return;
        }

        Material fillerMaterial = Material.GRAY_STAINED_GLASS_PANE;
        if (runtime != null) {
            try {
                fillerMaterial = Material.valueOf(runtime.config().gui().fillerMaterial().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                fillerMaterial = Material.GRAY_STAINED_GLASS_PANE;
            }
        }

        ItemStack filler = new ItemStack(fillerMaterial);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private ItemStack metricItem(String metric) {
        Material material = switch (metric) {
            case METRIC_ACTIVE -> Material.LIME_DYE;
            case METRIC_AFK -> Material.RED_DYE;
            default -> Material.EXPERIENCE_BOTTLE;
        };
        ChatColor color = switch (metric) {
            case METRIC_ACTIVE -> ChatColor.GREEN;
            case METRIC_AFK -> ChatColor.RED;
            default -> ChatColor.GOLD;
        };

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName((this.metric.equals(metric) ? ChatColor.BOLD.toString() : "") + color + niceMetric(metric));
        meta.setLore(List.of(this.metric.equals(metric) ? ChatColor.GREEN + "Selected" : ChatColor.YELLOW + "Click to sort by " + niceMetric(metric) + "."));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack rangeItem(String range) {
        Material material = switch (range) {
            case RANGE_TODAY -> Material.MAP;
            case RANGE_7D -> Material.NETHER_STAR;
            case RANGE_30D -> Material.PAPER;
            default -> Material.CLOCK;
        };

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName((this.range.equals(range) ? ChatColor.BOLD.toString() : "") + ChatColor.AQUA + niceRange(range));
        meta.setLore(List.of(this.range.equals(range) ? ChatColor.GREEN + "Selected" : ChatColor.YELLOW + "Click to switch range."));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private List<Integer> buildEntrySlots() {
        List<Integer> slots = new ArrayList<>();
        for (int row = 2; row <= 4; row++) {
            for (int col = 1; col <= 7; col++) {
                slots.add(row * 9 + col);
            }
        }
        return slots;
    }

    private ItemStack entryItem(LeaderboardEntry entry) {
        PlaytimeRuntime runtime = plugin.runtime();
        ItemStack head = runtime != null ? runtime.headCache().createHead(entry.uuid) : new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "#" + entry.rank + " " + resolveName(entry.uuid));
        meta.setLore(List.of(
                lineForMetric(METRIC_TOTAL, ChatColor.YELLOW, entry.totalMinutes),
                lineForMetric(METRIC_ACTIVE, ChatColor.GREEN, entry.activeMinutes),
                lineForMetric(METRIC_AFK, ChatColor.RED, entry.afkMinutes)
        ));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        head.setItemMeta(meta);
        return head;
    }

    private ItemStack selfItem(LeaderboardEntry selfEntry) {
        PlaytimeRuntime runtime = plugin.runtime();
        RangeTotals totals = runtime == null
                ? new RangeTotals(0, 0, 0)
                : runtime.readService().getRangeTotals(viewer.getUniqueId(), range);
        boolean loading = runtime != null && runtime.readService().isRangeLoading(viewer.getUniqueId(), range);

        ItemStack book = new ItemStack(Material.BOOK);
        ItemMeta meta = book.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "Your stats - " + niceMetric(metric) + ", " + niceRange(range));
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Rank: " + (selfEntry == null ? ChatColor.DARK_GRAY + "Not on this page" : ChatColor.YELLOW + "#" + selfEntry.rank));
        if (loading) {
            lore.add(ChatColor.YELLOW + "Refreshing cached stats...");
        }
        lore.add(lineForMetric(METRIC_TOTAL, ChatColor.YELLOW, totals.totalMinutes));
        lore.add(lineForMetric(METRIC_ACTIVE, ChatColor.GREEN, totals.activeMinutes));
        lore.add(lineForMetric(METRIC_AFK, ChatColor.RED, totals.afkMinutes));
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Compare this with the heads above.");
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        book.setItemMeta(meta);
        return book;
    }

    private String lineForMetric(String lineMetric, ChatColor valueColor, long minutes) {
        boolean highlighted = this.metric.equals(lineMetric);
        String prefix = switch (lineMetric) {
            case METRIC_ACTIVE -> "Active";
            case METRIC_AFK -> "AFK";
            default -> "Total";
        };
        return (highlighted ? ChatColor.BOLD.toString() : "") + ChatColor.GRAY + prefix + ": " + valueColor + TimeFormats.formatMinutes(minutes);
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

    private ItemStack loadingItem() {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + "Loading leaderboard...");
        meta.setLore(List.of(ChatColor.GRAY + "Cached data is being refreshed."));
        item.setItemMeta(meta);
        return item;
    }

    private void scheduleRefresh() {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!viewer.isOnline()) {
                    return;
                }
                if (viewer.getOpenInventory().getTopInventory().getHolder() instanceof PlaytimeGuiHolder holder
                        && holder.getGui() == this) {
                    render();
                }
            }, 20L);
        } catch (IllegalPluginAccessException exception) {
            if (plugin.isEnabled()) {
                throw exception;
            }
        }
    }

    private static String normalizeMetric(String metric) {
        String normalized = metric == null ? METRIC_TOTAL : metric.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case METRIC_ACTIVE, METRIC_AFK, METRIC_TOTAL -> normalized;
            default -> METRIC_TOTAL;
        };
    }

    private static String normalizeRange(String range) {
        String normalized = range == null ? RANGE_ALL : range.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case RANGE_TODAY, RANGE_7D, RANGE_30D, RANGE_ALL -> normalized;
            default -> RANGE_ALL;
        };
    }

    private static String niceMetric(String metric) {
        return switch (metric) {
            case METRIC_ACTIVE -> "Active";
            case METRIC_AFK -> "AFK";
            default -> "Total";
        };
    }

    private static String niceRange(String range) {
        return switch (range) {
            case RANGE_TODAY -> "Today";
            case RANGE_7D -> "Last 7 days";
            case RANGE_30D -> "Last 30 days";
            default -> "All time";
        };
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
        switch (slot) {
            case SLOT_PREV_PAGE -> previousPage();
            case SLOT_NEXT_PAGE -> nextPage();
            case SLOT_BACK -> new PlaytimeMainGui(plugin, viewer).open();
            case SLOT_CLOSE -> viewer.closeInventory();
            case SLOT_METRIC_ACTIVE -> selectMetric(METRIC_ACTIVE);
            case SLOT_METRIC_TOTAL -> selectMetric(METRIC_TOTAL);
            case SLOT_METRIC_AFK -> selectMetric(METRIC_AFK);
            case SLOT_RANGE_TODAY -> selectRange(RANGE_TODAY);
            case SLOT_RANGE_7D -> selectRange(RANGE_7D);
            case SLOT_RANGE_30D -> selectRange(RANGE_30D);
            case SLOT_RANGE_ALL -> selectRange(RANGE_ALL);
            default -> {
            }
        }
    }

    private void previousPage() {
        if (page <= 1) {
            return;
        }
        page--;
        render();
    }

    private void nextPage() {
        page++;
        render();
    }

    private void selectMetric(String selectedMetric) {
        metric = selectedMetric;
        page = 1;
        render();
    }

    private void selectRange(String selectedRange) {
        range = selectedRange;
        page = 1;
        render();
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
    }
}
