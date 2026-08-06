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
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.service.PlaytimeRuntime;
import org.enthusia.playtime.util.TimeFormats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class PlaytimeMainGui implements PlaytimeGui {

    private final PlayTimePlugin plugin;
    private final Player viewer;
    private final Inventory inventory;
    private final boolean bedrockLayout;

    private final int slotStats;
    private final int slotLeaderboard;
    private final int slotClose;

    public PlaytimeMainGui(PlayTimePlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        PlaytimeRuntime runtime = plugin.runtime();
        boolean bedrock = runtime != null && plugin.getBedrockSupport() != null
                && plugin.getBedrockSupport().isBedrock(viewer)
                && runtime.config().gui().bedrock().enabled();
        this.bedrockLayout = bedrock;
        int rows = bedrock ? runtime.config().gui().bedrock().mainMenuRows() : 3;
        int middleRow = Math.max(0, (rows - 1) / 2);
        int footer = (rows - 1) * 9;
        this.slotStats = middleRow * 9 + 4;
        this.slotLeaderboard = footer + 3;
        this.slotClose = footer + 5;
        this.inventory = Bukkit.createInventory(new PlaytimeGuiHolder(this), rows * 9,
                ChatColor.DARK_AQUA + "Your Playtime");
        render();
    }

    private void render() {
        inventory.clear();
        fillBackground();

        PlaytimeRuntime runtime = plugin.runtime();
        Optional<PlaytimeSnapshot> optional = runtime == null
                ? Optional.empty()
                : runtime.readService().getLifetime(viewer.getUniqueId());

        ItemStack statsItem = runtime != null ? runtime.headCache().createHead(viewer.getUniqueId()) : new ItemStack(Material.PLAYER_HEAD);
        ItemMeta statsMeta = statsItem.getItemMeta();
        statsMeta.setDisplayName(ChatColor.GOLD + viewer.getName() + ChatColor.YELLOW + "'s playtime");

        List<String> lore = new ArrayList<>();
        if (optional.isEmpty()) {
            if (runtime != null && runtime.readService().isLoading()) {
                runtime.counters().guiLoadingRenders.increment();
                lore.add(ChatColor.YELLOW + "Refreshing cached playtime...");
                scheduleRefresh();
            } else {
                lore.add(ChatColor.RED + "No playtime recorded yet.");
            }
        } else {
            PlaytimeSnapshot snapshot = optional.get();
            lore.add(ChatColor.GRAY + "Total: " + ChatColor.AQUA + TimeFormats.formatMinutes(snapshot.totalMinutes));
            lore.add(ChatColor.GRAY + "Active: " + ChatColor.GREEN + TimeFormats.formatMinutes(snapshot.activeMinutes));
            lore.add(ChatColor.GRAY + "AFK: " + ChatColor.RED + TimeFormats.formatMinutes(snapshot.afkMinutes));
        }
        lore.add("");
        lore.add(ChatColor.YELLOW + "Use the book below");
        lore.add(ChatColor.YELLOW + "to view leaderboards.");
        statsMeta.setLore(lore);
        statsMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        statsItem.setItemMeta(statsMeta);
        inventory.setItem(slotStats, statsItem);

        ItemStack leaderboard = new ItemStack(Material.BOOK);
        ItemMeta leaderboardMeta = leaderboard.getItemMeta();
        leaderboardMeta.setDisplayName(ChatColor.AQUA + "Leaderboards");
        leaderboardMeta.setLore(List.of(
                ChatColor.GRAY + "View top players by playtime.",
                "",
                ChatColor.YELLOW + "Left-click: " + ChatColor.WHITE + "Total, all time"
        ));
        leaderboardMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        leaderboard.setItemMeta(leaderboardMeta);
        inventory.setItem(slotLeaderboard, leaderboard);

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = close.getItemMeta();
        closeMeta.setDisplayName(ChatColor.RED + "Close");
        close.setItemMeta(closeMeta);
        inventory.setItem(slotClose, close);
    }

    private void fillBackground() {
        PlaytimeRuntime runtime = plugin.runtime();
        if (bedrockLayout) {
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
        if (slot == slotLeaderboard) {
            new LeaderboardGui(plugin, viewer, "TOTAL", "ALL", 1).open();
        } else if (slot == slotClose) {
            viewer.closeInventory();
        }
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
    }
}
