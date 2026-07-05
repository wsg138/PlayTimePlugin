package org.enthusia.playtime.gui.admin;

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
import org.enthusia.playtime.data.model.AdminServerStats;
import org.enthusia.playtime.gui.PlaytimeGui;
import org.enthusia.playtime.gui.PlaytimeGuiHolder;
import org.enthusia.playtime.service.PlaytimeRuntime;
import org.enthusia.playtime.util.TimeFormats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AdminServerActivityGui implements PlaytimeGui {
    private static final int SLOT_RANGE_TODAY = 10;
    private static final int SLOT_RANGE_7D = 12;
    private static final int SLOT_RANGE_30D = 14;
    private static final int SLOT_RANGE_ALL = 16;
    private static final int SLOT_BACK = 48;
    private static final int SLOT_CLOSE = 50;

    private final PlayTimePlugin plugin;
    private final Player viewer;
    private final Inventory inventory;

    private Range currentRange = Range.ALL;

    private enum Range {
        TODAY("TODAY", "Today"),
        SEVEN_DAYS("7D", "Last 7 days"),
        THIRTY_DAYS("30D", "Last 30 days"),
        ALL("ALL", "All time");

        private final String key;
        private final String label;

        Range(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    public AdminServerActivityGui(PlayTimePlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(new PlaytimeGuiHolder(this), 54, ChatColor.DARK_BLUE + "Playtime - Server Activity");
        render();
    }

    private void render() {
        inventory.clear();
        fillBackground();

        PlaytimeRuntime runtime = plugin.runtime();
        AdminServerStats stats = runtime == null ? new AdminServerStats() : runtime.readService().getAdminServerStats(currentRange.key);
        if (runtime != null && runtime.readService().isLoading()) {
            runtime.counters().guiLoadingRenders.increment();
        }

        inventory.setItem(SLOT_RANGE_TODAY, rangeItem(Range.TODAY, Material.FILLED_MAP));
        inventory.setItem(SLOT_RANGE_7D, rangeItem(Range.SEVEN_DAYS, Material.NETHER_STAR));
        inventory.setItem(SLOT_RANGE_30D, rangeItem(Range.THIRTY_DAYS, Material.PAPER));
        inventory.setItem(SLOT_RANGE_ALL, rangeItem(Range.ALL, Material.CLOCK));

        double retentionPct = stats.newPlayers > 0 ? (stats.retainedNewPlayers * 100.0D / stats.newPlayers) : 0.0D;
        double avgJoinsPerPlayer = stats.uniquePlayersJoined > 0 ? (stats.totalJoins * 1.0D / stats.uniquePlayersJoined) : 0.0D;

        inventory.setItem(20, buildItem(Material.PLAYER_HEAD, ChatColor.GOLD + "Players & Playtime", List.of(
                ChatColor.YELLOW + "Players with playtime: " + ChatColor.AQUA + stats.playersWithPlaytime,
                "",
                ChatColor.GRAY + "Active minutes: " + ChatColor.GREEN + TimeFormats.formatMinutes(stats.activeMinutes),
                ChatColor.YELLOW + "Avg unique players/day: " + ChatColor.AQUA + stats.avgUniquePlayersPerDay,
                ChatColor.YELLOW + "Peak unique/day: " + ChatColor.AQUA + stats.maxUniquePlayersPerDay
        )));

        inventory.setItem(22, buildItem(Material.OAK_DOOR, ChatColor.GOLD + "Joins", List.of(
                ChatColor.YELLOW + "Unique players joined: " + ChatColor.AQUA + stats.uniquePlayersJoined,
                ChatColor.YELLOW + "Total joins: " + ChatColor.AQUA + stats.totalJoins,
                "",
                ChatColor.YELLOW + "Avg joins/player: " + ChatColor.AQUA + String.format(Locale.US, "%.2f", avgJoinsPerPlayer),
                ChatColor.DARK_GRAY + "Uses plugin-owned join records."
        )));

        List<String> playerMixLore = new ArrayList<>();
        playerMixLore.add(ChatColor.YELLOW + "New players: " + ChatColor.AQUA + stats.newPlayers);
        playerMixLore.add(ChatColor.YELLOW + "Returning players: " + ChatColor.AQUA + stats.returningPlayers);
        playerMixLore.add("");
        playerMixLore.add(ChatColor.YELLOW + "Retained new players: " + ChatColor.AQUA + stats.retainedNewPlayers);
        playerMixLore.add(ChatColor.YELLOW + "Retention: " + ChatColor.AQUA + String.format(Locale.US, "%.1f%%", retentionPct));
        playerMixLore.add("");
        playerMixLore.add(ChatColor.DARK_GRAY + "New players are based only on");
        playerMixLore.add(ChatColor.DARK_GRAY + "this plugin's own storage.");
        inventory.setItem(24, buildItem(Material.EMERALD, ChatColor.GOLD + "New vs Returning", playerMixLore));

        inventory.setItem(31, buildItem(Material.BOOK, ChatColor.GOLD + "How to read this", List.of(
                ChatColor.GRAY + "Range: " + ChatColor.AQUA + currentRange.label,
                "",
                ChatColor.GRAY + "New players: first plugin-owned",
                ChatColor.GRAY + "join in the selected period.",
                ChatColor.GRAY + "Returning: joined before the",
                ChatColor.GRAY + "period and came back in it."
        )));

        inventory.setItem(SLOT_BACK, buildItem(Material.OAK_DOOR, ChatColor.YELLOW + "Back", List.of()));
        inventory.setItem(SLOT_CLOSE, buildItem(Material.BARRIER, ChatColor.RED + "Close", List.of()));
    }

    private void fillBackground() {
        boolean bedrock = plugin.runtime() != null && plugin.getBedrockSupport() != null && plugin.getBedrockSupport().isBedrock(viewer);
        if (bedrock) {
            return;
        }
        Material filler = Material.GRAY_STAINED_GLASS_PANE;
        if (plugin.runtime() != null) {
            try {
                filler = Material.valueOf(plugin.runtime().config().gui().fillerMaterial().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                filler = Material.GRAY_STAINED_GLASS_PANE;
            }
        }
        ItemStack fillerItem = new ItemStack(filler);
        ItemMeta fillerMeta = fillerItem.getItemMeta();
        fillerMeta.setDisplayName(" ");
        fillerItem.setItemMeta(fillerMeta);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, fillerItem);
        }
    }

    private ItemStack rangeItem(Range range, Material material) {
        boolean selected = range == currentRange;
        return buildItem(material,
                (selected ? ChatColor.GREEN + "* " : ChatColor.YELLOW + "- ") + range.label,
                List.of(
                        ChatColor.GRAY + "Click to view:",
                        ChatColor.AQUA + range.label,
                        selected ? ChatColor.GREEN + "Currently selected" : ChatColor.YELLOW + "Switch range"
                ));
    }

    private ItemStack buildItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
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
            case SLOT_RANGE_TODAY -> selectRange(Range.TODAY);
            case SLOT_RANGE_7D -> selectRange(Range.SEVEN_DAYS);
            case SLOT_RANGE_30D -> selectRange(Range.THIRTY_DAYS);
            case SLOT_RANGE_ALL -> selectRange(Range.ALL);
            case SLOT_BACK -> new AdminMainGui(plugin, viewer).open();
            case SLOT_CLOSE -> viewer.closeInventory();
            default -> {
            }
        }
    }

    private void selectRange(Range range) {
        currentRange = range;
        render();
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
    }
}
