package org.enthusia.playtime.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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

        inventory.setItem(SLOT_METRIC_ACTIVE, metricItem("ACTIVE"));
        inventory.setItem(SLOT_METRIC_TOTAL, metricItem("TOTAL"));
        inventory.setItem(SLOT_METRIC_AFK, metricItem("AFK"));
        inventory.setItem(SLOT_RANGE_TODAY, rangeItem("TODAY"));
        inventory.setItem(SLOT_RANGE_7D, rangeItem("7D"));
        inventory.setItem(SLOT_RANGE_30D, rangeItem("30D"));
        inventory.setItem(SLOT_RANGE_ALL, rangeItem("ALL"));

        List<Integer> entrySlots = buildEntrySlots();
        int pageSize = entrySlots.size();
        PlaytimeRuntime runtime = plugin.runtime();
        List<LeaderboardEntry> entries = runtime == null
                ? List.of()
                : runtime.readService().getLeaderboard(metric, range, pageSize, (page - 1) * pageSize);

        LeaderboardEntry selfEntry = null;
        for (int index = 0; index < entries.size() && index < entrySlots.size(); index++) {
            LeaderboardEntry entry = entries.get(index);
            inventory.setItem(entrySlots.get(index), entryItem(entry));
            if (entry.uuid.equals(viewer.getUniqueId())) {
                selfEntry = entry;
            }
        }

        ItemStack prev = new ItemStack(Material.ARROW);
        ItemMeta prevMeta = prev.getItemMeta();
        prevMeta.setDisplayName(ChatColor.YELLOW + "Previous page (" + Math.max(page - 1, 1) + ")");
        prev.setItemMeta(prevMeta);
        inventory.setItem(SLOT_PREV_PAGE, prev);

        ItemStack next = new ItemStack(Material.ARROW);
        ItemMeta nextMeta = next.getItemMeta();
        nextMeta.setDisplayName(ChatColor.YELLOW + "Next page (" + (page + 1) + ")");
        next.setItemMeta(nextMeta);
        inventory.setItem(SLOT_NEXT_PAGE, next);

        ItemStack back = new ItemStack(Material.OAK_DOOR);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName(ChatColor.AQUA + "Back to main menu");
        back.setItemMeta(backMeta);
        inventory.setItem(SLOT_BACK, back);

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = close.getItemMeta();
        closeMeta.setDisplayName(ChatColor.RED + "Close");
        close.setItemMeta(closeMeta);
        inventory.setItem(SLOT_CLOSE, close);

        inventory.setItem(SLOT_SELF, selfItem(selfEntry));
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
            case "ACTIVE" -> Material.LIME_DYE;
            case "AFK" -> Material.RED_DYE;
            default -> Material.EXPERIENCE_BOTTLE;
        };
        ChatColor color = switch (metric) {
            case "ACTIVE" -> ChatColor.GREEN;
            case "AFK" -> ChatColor.RED;
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
            case "TODAY" -> Material.MAP;
            case "7D" -> Material.NETHER_STAR;
            case "30D" -> Material.PAPER;
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
                lineForMetric("TOTAL", ChatColor.YELLOW, entry.totalMinutes),
                lineForMetric("ACTIVE", ChatColor.GREEN, entry.activeMinutes),
                lineForMetric("AFK", ChatColor.RED, entry.afkMinutes)
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

        ItemStack book = new ItemStack(Material.BOOK);
        ItemMeta meta = book.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "Your stats - " + niceMetric(metric) + ", " + niceRange(range));
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Rank: " + (selfEntry == null ? ChatColor.DARK_GRAY + "Not on this page" : ChatColor.YELLOW + "#" + selfEntry.rank));
        lore.add(lineForMetric("TOTAL", ChatColor.YELLOW, totals.totalMinutes));
        lore.add(lineForMetric("ACTIVE", ChatColor.GREEN, totals.activeMinutes));
        lore.add(lineForMetric("AFK", ChatColor.RED, totals.afkMinutes));
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
            case "ACTIVE" -> "Active";
            case "AFK" -> "AFK";
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
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        return offline.getName() != null ? offline.getName() : uuid.toString().substring(0, 8);
    }

    private static String normalizeMetric(String metric) {
        String normalized = metric == null ? "TOTAL" : metric.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ACTIVE", "AFK", "TOTAL" -> normalized;
            default -> "TOTAL";
        };
    }

    private static String normalizeRange(String range) {
        String normalized = range == null ? "ALL" : range.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "TODAY", "7D", "30D", "ALL" -> normalized;
            default -> "ALL";
        };
    }

    private static String niceMetric(String metric) {
        return switch (metric) {
            case "ACTIVE" -> "Active";
            case "AFK" -> "AFK";
            default -> "Total";
        };
    }

    private static String niceRange(String range) {
        return switch (range) {
            case "TODAY" -> "Today";
            case "7D" -> "Last 7 days";
            case "30D" -> "Last 30 days";
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
        if (slot == SLOT_METRIC_ACTIVE) {
            metric = "ACTIVE";
            page = 1;
            render();
            return;
        }
        if (slot == SLOT_METRIC_TOTAL) {
            metric = "TOTAL";
            page = 1;
            render();
            return;
        }
        if (slot == SLOT_METRIC_AFK) {
            metric = "AFK";
            page = 1;
            render();
            return;
        }
        if (slot == SLOT_RANGE_TODAY) {
            range = "TODAY";
            page = 1;
            render();
            return;
        }
        if (slot == SLOT_RANGE_7D) {
            range = "7D";
            page = 1;
            render();
            return;
        }
        if (slot == SLOT_RANGE_30D) {
            range = "30D";
            page = 1;
            render();
            return;
        }
        if (slot == SLOT_RANGE_ALL) {
            range = "ALL";
            page = 1;
            render();
        }
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
    }
}
